package com.thkim.gamebooster;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private WebView web;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("gb", MODE_PRIVATE);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B1220);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Native");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);

        // 업데이트: 설치 결과 수신, 백그라운드 확인 예약, 알림 권한
        IntentFilter f = new IntentFilter(Updater.ACTION_INSTALL_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(installReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(installReceiver, f);
        Updater.schedule(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("askedNotif", false)) {
            prefs.edit().putBoolean("askedNotif", true).apply();
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        if (getIntent().getBooleanExtra("fromUpdate", false)) openUpdateOnLoad = true;
    }

    private boolean openUpdateOnLoad = false;

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("fromUpdate", false))
            web.evaluateJavascript("window.openUpdate && openUpdate()", null);
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(installReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private final android.content.BroadcastReceiver installReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999);
            if (st == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(confirm); } catch (Exception e) { callJs("onUpdateError", "설치 화면을 열 수 없어요"); }
                }
                callJs("onUpdateStage", "confirm");
            } else if (st != android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                String msg = i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE);
                if (st == android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED) msg = "설치를 취소했어요";
                else if (st == android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT) msg = "서명이 달라 업데이트할 수 없어요. 기존 앱을 지운 뒤 새로 설치해 주세요";
                else if (st == android.content.pm.PackageInstaller.STATUS_FAILURE_STORAGE) msg = "저장공간이 부족해요";
                callJs("onUpdateError", msg == null ? "설치 실패" : msg);
            }
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.onAppResume && onAppResume()", null);
        // 게임하다 돌아오면 기록을 끝내고 리포트 표시
        if (SessionService.isRecording(this)) {
            new Thread(() -> {
                JSONObject rep = SessionService.finish(MainActivity.this);
                try { stopService(new Intent(MainActivity.this, SessionService.class)); } catch (Exception ignored) {}
                if (rep != null) {
                    prefs.edit().remove("pendingReport").apply();
                    callJs("onSessionReport", rep.toString());
                } else if (SessionService.lastWasRecord) callJs("onSessionShort", "");
            }).start();
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onBack ? onBack() : false", value -> {
            if (!"true".equals(value)) MainActivity.super.onBackPressed();
        });
    }

    private void callJs(final String fn, final String json) {
        runOnUiThread(() -> web.evaluateJavascript(fn + "(" + JSONObject.quote(json) + ")", null));
    }

    // ---------- 메모리 ----------
    private long[] mem() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return new long[]{mi.totalMem, mi.availMem};
    }

    private List<ResolveInfo> launchables() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        return getPackageManager().queryIntentActivities(i, 0);
    }

    private JSONObject doBoost(String keepPkg) throws Exception {
        long before = mem()[1];
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        PackageManager pm = getPackageManager();
        Set<String> done = new HashSet<>();
        // 실행 중인 백그라운드 앱 + 설치된 런처 앱 대상
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            String p = ai.packageName;
            if (p.equals(getPackageName()) || p.equals(keepPkg)) continue;
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) continue;
            try { am.killBackgroundProcesses(p); done.add(p); } catch (Exception ignored) {}
        }
        // 이 앱 자체 캐시 정리
        long cache = dirSize(getCacheDir());
        deleteChildren(getCacheDir());
        System.gc();
        Thread.sleep(1200);
        long after = mem()[1];
        boolean dnd = false;
        // 게임 실행 때는 게임 모드가 방해금지를 맡음 (끝나면 복원). 부스트 버튼만 누를 땐 예전처럼.
        if ((keepPkg == null || keepPkg.isEmpty()) && prefs.getBoolean("autoDnd", false) && dndGranted()) { setDndInternal(true); dnd = true; }
        prefs.edit().putLong("lastBoost", System.currentTimeMillis()).apply();
        JSONObject r = new JSONObject();
        r.put("before", before);
        r.put("after", after);
        r.put("freed", Math.max(0, after - before));
        r.put("count", done.size());
        r.put("cache", cache);
        r.put("dnd", dnd);
        r.put("total", mem()[0]);
        return r;
    }

    private long dirSize(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long t = 0;
        File[] c = f.listFiles();
        if (c != null) for (File x : c) t += dirSize(x);
        return t;
    }

    private void deleteChildren(File f) {
        File[] c = f == null ? null : f.listFiles();
        if (c == null) return;
        for (File x : c) { if (x.isDirectory()) deleteChildren(x); x.delete(); }
    }

    // ---------- 방해금지 ----------
    private boolean dndGranted() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        return nm.isNotificationPolicyAccessGranted();
    }

    private void setDndInternal(boolean on) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_PRIORITY
                : NotificationManager.INTERRUPTION_FILTER_ALL);
    }

    private String iconB64(Drawable d) {
        int size = 96;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, bos);
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }

    private Set<String> myGames() {
        if (!prefs.contains("games")) {
            Set<String> auto = new HashSet<>();
            for (ResolveInfo ri : launchables()) {
                ApplicationInfo ai = ri.activityInfo.applicationInfo;
                if (ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0)
                    auto.add(ai.packageName);
            }
            prefs.edit().putStringSet("games", auto).apply();
            return auto;
        }
        return new HashSet<>(prefs.getStringSet("games", new HashSet<>()));
    }

    private static String readFirstLine(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            return br.readLine();
        } catch (Exception e) { return null; }
    }

    class Bridge {

        @JavascriptInterface
        public String getVersion() {
            try {
                JSONObject o = new JSONObject();
                o.put("name", Updater.myVersionName(MainActivity.this));
                o.put("code", Updater.myVersionCode(MainActivity.this));
                o.put("openUpdate", openUpdateOnLoad);
                openUpdateOnLoad = false;
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void checkUpdate() {
            new Thread(() -> {
                JSONObject r = Updater.check(MainActivity.this);
                callJs("onUpdateInfo", r.toString());
            }).start();
        }

        /** 반환: "ok"(다운로드 시작) / "permission"(출처 허용 필요) */
        @JavascriptInterface
        public String installUpdate(final String url) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                runOnUiThread(() -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
                });
                return "permission";
            }
            new Thread(() -> {
                try {
                    Updater.downloadAndInstall(MainActivity.this, url, pct -> callJs("onUpdateProgress", String.valueOf(pct)));
                    callJs("onUpdateStage", "installing");
                } catch (Exception e) {
                    callJs("onUpdateError", "다운로드 실패: " + e.getMessage());
                }
            }).start();
            return "ok";
        }

        @JavascriptInterface
        public void openReleases() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Updater.RELEASES_PAGE))); }
                catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public String getStatus() {
            try {
                JSONObject o = new JSONObject();
                Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (bat != null) {
                    int lvl = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                    o.put("battery", lvl * 100 / Math.max(1, scale));
                    o.put("temp", bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);
                    int st = bat.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                    o.put("charging", st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL);
                }
                long[] m = mem();
                o.put("ramTotal", m[0]);
                o.put("ramAvail", m[1]);
                StatFs sf = new StatFs(Environment.getDataDirectory().getPath());
                o.put("diskTotal", sf.getTotalBytes());
                o.put("diskFree", sf.getAvailableBytes());
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                String net = "연결 없음";
                Network n = cm.getActiveNetwork();
                if (n != null) {
                    NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                    if (nc != null) {
                        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) net = "와이파이";
                        else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) net = "모바일 데이터";
                        else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) net = "유선";
                        else net = "기타";
                    }
                }
                o.put("net", net);
                o.put("cores", Runtime.getRuntime().availableProcessors());
                String mf = readFirstLine("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq");
                o.put("cpuMaxMhz", mf == null ? 0 : Long.parseLong(mf.trim()) / 1000);
                o.put("model", Build.MANUFACTURER + " " + Build.MODEL);
                o.put("android", Build.VERSION.RELEASE);
                o.put("dndGranted", dndGranted());
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                o.put("dndOn", nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL);
                o.put("autoDnd", prefs.getBoolean("autoDnd", false));
                o.put("lastBoost", prefs.getLong("lastBoost", 0));
                PowerHolder.fill(MainActivity.this, o);
                return o.toString();
            } catch (Exception e) {
                return "{\"error\":" + JSONObject.quote(String.valueOf(e)) + "}";
            }
        }

        @JavascriptInterface
        public void boost() {
            new Thread(() -> {
                try { callJs("onBoostDone", doBoost("").toString()); }
                catch (Exception e) { callJs("onBoostDone", "{\"error\":" + JSONObject.quote(String.valueOf(e)) + "}"); }
            }).start();
        }

        @JavascriptInterface
        public void setAutoDnd(boolean on) {
            prefs.edit().putBoolean("autoDnd", on).apply();
            try {  // 기본 게임 모드의 방해금지와 같은 설정
                JSONObject d = new JSONObject(GameMode.defaults(MainActivity.this).toString());
                d.put("dnd", on);
                GameMode.save(MainActivity.this, GameMode.DEFAULT, d);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public boolean setDnd(boolean on) {
            if (!dndGranted()) {
                runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));
                return false;
            }
            setDndInternal(on);
            return true;
        }

        @JavascriptInterface
        public void openDndPermission() {
            runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));
        }

        @JavascriptInterface
        public void openSettings(String which) {
            runOnUiThread(() -> {
                try {
                    String a;
                    switch (which) {
                        case "battery": a = Settings.ACTION_BATTERY_SAVER_SETTINGS; break;
                        case "display": a = Settings.ACTION_DISPLAY_SETTINGS; break;
                        case "wifi": a = Settings.ACTION_WIFI_SETTINGS; break;
                        case "storage": a = Settings.ACTION_INTERNAL_STORAGE_SETTINGS; break;
                        default: a = Settings.ACTION_SETTINGS;
                    }
                    startActivity(new Intent(a));
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                }
            });
        }

        @JavascriptInterface
        public void loadApps(final boolean all) {
            new Thread(() -> {
                try {
                    PackageManager pm = getPackageManager();
                    Set<String> games = myGames();
                    JSONArray arr = new JSONArray();
                    Set<String> seen = new HashSet<>();
                    for (ResolveInfo ri : launchables()) {
                        String p = ri.activityInfo.packageName;
                        if (p.equals(getPackageName()) || !seen.add(p)) continue;
                        boolean mine = games.contains(p);
                        if (!all && !mine) continue;
                        ApplicationInfo ai = ri.activityInfo.applicationInfo;
                        JSONObject o = new JSONObject();
                        o.put("pkg", p);
                        o.put("label", ri.loadLabel(pm).toString());
                        o.put("mine", mine);
                        o.put("isGame", ai.category == ApplicationInfo.CATEGORY_GAME);
                        o.put("icon", iconB64(ri.loadIcon(pm)));
                        arr.put(o);
                    }
                    callJs(all ? "onAllApps" : "onMyGames", arr.toString());
                } catch (Exception e) {
                    callJs(all ? "onAllApps" : "onMyGames", "[]");
                }
            }).start();
        }

        @JavascriptInterface
        public void setGame(String pkg, boolean on) {
            Set<String> g = myGames();
            if (on) g.add(pkg); else g.remove(pkg);
            prefs.edit().putStringSet("games", g).apply();
        }

        @JavascriptInterface
        public void launchGame(final String pkg, final boolean boostFirst) {
            new Thread(() -> {
                try {
                    long memNow = mem()[1];
                    JSONObject br = boostFirst ? doBoost(pkg) : new JSONObject();
                    Intent li = getPackageManager().getLaunchIntentForPackage(pkg);
                    if (li == null) { callJs("onLaunchFail", pkg); return; }
                    li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    boolean rec = prefs.getBoolean("record", true);
                    String label = pkg;
                    try { label = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
                    catch (Exception ignored) {}
                    // 게임 모드는 항상, 기록은 설정에 따라
                    SessionService.begin(MainActivity.this, pkg, label, boostFirst,
                            br.optLong("before", memNow), br.optLong("after", memNow), rec);
                    br.put("recording", rec);
                    callJs("onLaunching", br.toString());
                    Thread.sleep(600);
                    runOnUiThread(() -> startActivity(li));
                } catch (Exception e) {
                    callJs("onLaunchFail", pkg);
                }
            }).start();
        }

        @JavascriptInterface
        public void compareNetworks() {
            new Thread(() -> {
                JSONObject r = NetCompare.run(MainActivity.this);
                prefs.edit().putString("lastNetCompare", r.toString()).apply();
                callJs("onNetCompare", r.toString());
            }).start();
        }

        @JavascriptInterface
        public String lastNetCompare() { return prefs.getString("lastNetCompare", ""); }

        @JavascriptInterface
        public void openInternetPanel() {
            runOnUiThread(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= 29) startActivity(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY));
                    else startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                } catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            });
        }

        @JavascriptInterface
        public String getSessions() { return prefs.getString("sessions", "[]"); }

        @JavascriptInterface
        public void deleteSession(String id) {
            try {
                JSONArray all = new JSONArray(prefs.getString("sessions", "[]")), out = new JSONArray();
                for (int i = 0; i < all.length(); i++)
                    if (!String.valueOf(all.getJSONObject(i).optLong("id")).equals(id)) out.put(all.get(i));
                prefs.edit().putString("sessions", out.toString()).apply();
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public String getGameMode(String pkg) {
            try {
                JSONObject o = new JSONObject();
                JSONObject g = GameMode.all(MainActivity.this).optJSONObject(pkg);
                o.put("mode", pkg.equals(GameMode.DEFAULT) ? GameMode.defaults(MainActivity.this)
                        : (g != null ? g : new JSONObject(GameMode.defaults(MainActivity.this).toString()).put("own", false)));
                o.put("defaults", GameMode.defaults(MainActivity.this));
                o.put("dndGranted", dndGranted());
                o.put("overlay", Hud.canShow(MainActivity.this));
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void setGameMode(String pkg, String json) {
            try {
                JSONObject m = new JSONObject(json);
                GameMode.save(MainActivity.this, pkg, m);
                if (pkg.equals(GameMode.DEFAULT)) prefs.edit().putBoolean("autoDnd", m.optBoolean("dnd")).apply();
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void openOverlayPermission() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + getPackageName())));
                } catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            });
        }

        @JavascriptInterface
        public boolean getRecord() { return prefs.getBoolean("record", true); }

        /** HUD 켜짐 = 설정 켜짐 + 권한 있음 */
        @JavascriptInterface
        public boolean getHud() { return prefs.getBoolean("hud", false) && Hud.canShow(MainActivity.this); }

        /** 반환: "ok" / "permission"(권한 화면을 열었음) */
        @JavascriptInterface
        public String setHud(boolean on) {
            if (on && !Hud.canShow(MainActivity.this)) {
                runOnUiThread(() -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
                });
                return "permission";
            }
            prefs.edit().putBoolean("hud", on).apply();
            if (on) prefs.edit().putBoolean("record", true).apply(); // HUD는 기록과 함께 동작
            return "ok";
        }

        @JavascriptInterface
        public void setRecord(boolean on) { prefs.edit().putBoolean("record", on).apply(); }

        /** 알림에서 끝낸 기록처럼 아직 안 본 리포트의 id (없으면 0) */
        @JavascriptInterface
        public String takePendingReport() {
            long id = prefs.getLong("pendingReport", 0);
            prefs.edit().remove("pendingReport").apply();
            return String.valueOf(id);
        }

        @JavascriptInterface
        public void ping() {
            new Thread(() -> {
                String[] hosts = {"8.8.8.8", "1.1.1.1", "168.126.63.1"};
                long best = -1, sum = 0; int ok = 0;
                for (int round = 0; round < 2; round++) {
                    for (String h : hosts) {
                        long t0 = System.nanoTime();
                        try (Socket s = new Socket()) {
                            s.connect(new InetSocketAddress(h, 53), 2000);
                            long ms = (System.nanoTime() - t0) / 1_000_000;
                            sum += ms; ok++;
                            if (best < 0 || ms < best) best = ms;
                        } catch (Exception ignored) {}
                    }
                }
                try {
                    JSONObject o = new JSONObject();
                    o.put("best", best);
                    o.put("avg", ok == 0 ? -1 : sum / ok);
                    o.put("ok", ok);
                    callJs("onPing", o.toString());
                } catch (Exception e) { callJs("onPing", "{\"best\":-1}"); }
            }).start();
        }
    }

    /** 절전 모드 상태 */
    static class PowerHolder {
        static void fill(Context c, JSONObject o) throws Exception {
            android.os.PowerManager pw = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
            o.put("powerSave", pw.isPowerSaveMode());
            if (Build.VERSION.SDK_INT >= 29) o.put("thermal", pw.getCurrentThermalStatus());
        }
    }
}
