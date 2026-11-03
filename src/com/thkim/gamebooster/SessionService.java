package com.thkim.gamebooster;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.SharedPreferences;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 게임 세션 서비스.
 * - 세션: 게임하는 동안 기록(10초마다), HUD, 게임 모드, 휴식 알림
 * - 감시 모드(자동 감지 켜짐): 홈 화면에서 켠 게임도 알아채서 세션을 자동으로 시작·종료
 */
public class SessionService extends Service {

    static final String ACTION_STOP = "com.thkim.gamebooster.SESSION_STOP";
    static final String ACTION_WATCH = "com.thkim.gamebooster.WATCH";
    static final String ACTION_UNWATCH = "com.thkim.gamebooster.UNWATCH";
    static final String ACTION_SHOW_HUD = "com.thkim.gamebooster.SHOW_HUD";
    static final String ACTION_ENDED = "com.thkim.gamebooster.SESSION_ENDED";
    private static final String CH = "session", CH_WATCH = "watch", CH_REPORT = "report";
    private static final int NOTI_ID = 77;
    private static final long INTERVAL_MS = 10_000;  // 기록 간격
    private static final long HUD_MS = 2_000;        // 틱 간격
    private static final long LEAVE_MS = 90_000;     // 게임을 떠나고 이만큼 지나면 종료 (로그인·결제 화면에 다녀올 여유)
    private static final long MAX_MS = 4L * 60 * 60 * 1000; // 최대 4시간
    static final long MIN_SEC = 30;                            // 30초 미만은 버림

    private final Handler h = new Handler(Looper.getMainLooper());
    private long start;
    private Hud hud;
    private int ticks;
    private android.view.View keepView;   // 화면 켜짐 유지용 투명 점
    private int breakMin;                 // 휴식 알림 간격(분), 0이면 끔
    private long nextBreak;
    private boolean inSession;
    private boolean hudClosed;            // 이번 게임에서 사용자가 HUD를 닫았음
    private String sessionPkg;
    private long leftAt;                  // 게임에서 다른 앱으로 간 시각 (0 = 게임 중)
    private String fgPkg;                 // 지금 화면 맨 앞 앱
    private long lastQuery;
    private boolean thermalArmed = true;
    private boolean heatArmed = true, batArmed = true;   // 실시간 경고 (한 번 알리면 회복될 때까지 쉼)
    static final double HEAT_ALERT = 45.0, HEAT_REARM = 42.0;
    static final int BAT_ALERT = 15;
    static volatile boolean lastShort;             // 방금 끝난 판이 30초 미만이라 버려졌음
    static final long DRAIN_MIN_SEC = 900;          // 시간당 소모를 계산할 최소 시간 (15분)
    static final int DRAIN_MIN_USED = 2;            // 잔량은 1% 단위라 이만큼은 줄어야 계산

    static File metaFile(Context c) { return new File(c.getFilesDir(), "session_meta.json"); }
    static File dataFile(Context c) { return new File(c.getFilesDir(), "session_data.csv"); }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("gb", MODE_PRIVATE); }

    // ---------- 자동 감지 설정 ----------

    static boolean hasUsageAccess(Context c) {
        try {
            AppOpsManager ao = (AppOpsManager) c.getSystemService(APP_OPS_SERVICE);
            int mode = Build.VERSION.SDK_INT >= 29
                    ? ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.getPackageName())
                    : ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) { return false; }
    }

    static boolean watchEnabled(Context c) { return prefs(c).getBoolean("autoDetect", false) && hasUsageAccess(c); }

    /** 자동 감지가 켜져 있으면 감시 시작 (앱 실행·부팅 때 부름) */
    static void ensureWatching(Context c) {
        if (!watchEnabled(c)) return;
        try {
            c.startForegroundService(new Intent(c, SessionService.class).setAction(ACTION_WATCH));
        } catch (Exception ignored) {}
    }

    static void stopWatching(Context c) {
        try { c.startService(new Intent(c, SessionService.class).setAction(ACTION_UNWATCH)); } catch (Exception ignored) {}
    }

    // ---------- 세션 시작 ----------

    /** 세션 파일 준비 + 게임 모드 적용 (서비스 시작은 하지 않음) */
    static boolean prepare(Context c, String pkg, String label, boolean boosted, long ramBefore, long ramAfter, boolean record) {
        try {
            if (metaFile(c).exists()) finish(c);        // 끝나지 않은 이전 세션 정리 + 설정 복원
            JSONObject mode = GameMode.apply(c, pkg);   // 게임별 모드 적용 (끝나면 복원)
            boolean adWanted = mode.optBoolean("adBlock");
            boolean adBlock = adWanted && AdBlockVpn.permitted(c);
            JSONObject m = new JSONObject();
            m.put("adBlock", adBlock);
            m.put("adWanted", adWanted);
            m.put("record", record);
            m.put("keepScreen", mode.optBoolean("keepScreen"));
            m.put("breakMin", mode.optInt("breakMin", 0));
            m.put("pkg", pkg);
            m.put("label", label);
            try { m.put("ver", c.getPackageManager().getPackageInfo(pkg, 0).versionName); } catch (Exception ignored) {}
            m.put("start", System.currentTimeMillis());
            m.put("boosted", boosted);
            m.put("ramBefore", ramBefore);
            m.put("ramAfter", ramAfter);
            try (FileWriter w = new FileWriter(metaFile(c))) { w.write(m.toString()); }
            dataFile(c).delete();
            if (adBlock) AdBlockVpn.start(c, pkg);     // 이 게임만 광고 차단 (끝나면 해제)
            return true;
        } catch (Exception e) { return false; }
    }

    /** 게임부스터에서 게임을 실행하기 직전에 부른다 */
    static void begin(Context c, String pkg, String label, boolean boosted, long ramBefore, long ramAfter, boolean record) {
        if (!prepare(c, pkg, label, boosted, ramBefore, ramAfter, record)) return;
        try { c.startForegroundService(new Intent(c, SessionService.class)); } catch (Exception ignored) {}
    }

    static boolean isRecording(Context c) { return metaFile(c).exists(); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            endSession(intent.getBooleanExtra("open", false), false);
            return START_STICKY;
        }
        if (ACTION_SHOW_HUD.equals(action)) {
            if (inSession) { hudClosed = false; createHud(300); refreshNoti(); }
            return START_STICKY;
        }
        if (ACTION_UNWATCH.equals(action)) {
            if (!inSession) { stopAll(); return START_NOT_STICKY; }
            return START_STICKY;            // 세션 중이면 세션이 끝날 때 멈춤
        }
        if (ACTION_ENDED.equals(action)) {  // 게임부스터 화면에서 세션을 끝냈음
            closeSessionUi();
            afterSession();
            return START_STICKY;
        }
        if (ACTION_WATCH.equals(action)) {
            if (!inSession && !goForeground(watchNoti())) { stopSelf(); return START_NOT_STICKY; }
            lastQuery = System.currentTimeMillis() - 5_000;
            kick();
            return START_STICKY;
        }
        // 기본: 세션 시작 (재시작된 경우엔 남은 세션 이어서)
        if (metaFile(this).exists()) startSessionUi();
        else if (watchEnabled(this)) {
            if (!goForeground(watchNoti())) { stopSelf(); return START_NOT_STICKY; }
            kick();
        }
        else { stopSelf(); return START_NOT_STICKY; }
        return START_STICKY;
    }

    /** 시스템이 서비스를 되살린 경우 안드로이드 12+는 백그라운드 포그라운드 전환을 막아 예외를 던진다 */
    private boolean goForeground(Notification n) {
        try { startForeground(NOTI_ID, n); return true; }
        catch (Exception e) { return false; }
    }

    private void kick() { h.removeCallbacks(tick); h.post(tick); }

    /** 세션 화면 요소(알림·HUD·화면 유지) 시작 */
    private void startSessionUi() {
        start = readStart(this);
        if (!goForeground(buildNoti())) {   // 이어갈 수 없으면 방해금지·볼륨을 되돌리고 기록을 마무리
            finish(this);
            stopSelf();
            return;
        }
        inSession = true;
        leftAt = 0;
        if (!watchEnabled(this)) {   // 감시 중이 아니었으면 이전 판의 앞 화면 정보를 버림
            fgPkg = null;
            lastQuery = System.currentTimeMillis() - 5_000;
        }
        heatArmed = true; batArmed = true; thermalArmed = true;
        ticks = 0;
        try {
            JSONObject meta = new JSONObject(readAll(metaFile(this)));
            sessionPkg = meta.optString("pkg");
            breakMin = meta.optInt("breakMin", 0);
            nextBreak = breakMin > 0 ? start + breakMin * 60_000L : Long.MAX_VALUE;
            if (meta.optBoolean("keepScreen") && keepView == null && Hud.canShow(this)) addKeepScreen();
        } catch (Exception ignored) {}
        hudClosed = false;
        if (prefs(this).getBoolean("hud", false)) createHud(1500);   // 게임이 뜬 뒤에 보이도록
        kick();
    }

    /** HUD 만들기 (닫기 → HUD만 숨김, 게임 모드·기록은 계속) */
    private void createHud(long delay) {
        if (hud != null || !Hud.canShow(this)) return;
        String label = "게임";
        try { label = new JSONObject(readAll(metaFile(this))).optString("label", "게임"); } catch (Exception ignored) {}
        hud = new Hud(this, label, start, new Hud.Listener() {
            @Override public void onFinish() { endSession(true, false); }
            @Override public void onClose() {
                if (hud != null) { hud.hide(); hud = null; }
                hudClosed = true;
                refreshNoti();
                android.widget.Toast.makeText(SessionService.this,
                        "HUD만 닫았어요. 게임 모드·기록은 계속돼요 (알림창에서 다시 열 수 있어요)", android.widget.Toast.LENGTH_LONG).show();
            }
        });
        h.postDelayed(() -> { if (hud != null) hud.show(); }, delay);
    }

    private void refreshNoti() {
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTI_ID, buildNoti()); } catch (Exception ignored) {}
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            boolean watching = watchEnabled(SessionService.this);
            if (inSession && !metaFile(SessionService.this).exists()) { closeSessionUi(); afterSession(); return; }
            // 사용 정보 권한이 있으면 자동 감지가 꺼져 있어도 게임을 떠난 것을 알아챔
            boolean canSee = watching || (inSession && hasUsageAccess(SessionService.this));
            if (canSee) updateForeground();

            if (inSession) {
                if (ticks % (INTERVAL_MS / HUD_MS) == 0) sample(SessionService.this);
                ticks++;
                if (hud != null) hud.update();
                checkAlerts();
                long now = System.currentTimeMillis();
                if (now >= nextBreak) {
                    int played = (int) ((now - start) / 60_000);
                    GameMode.breakNotice(SessionService.this, played);
                    if (hud != null) hud.flash(played + "분 했어요 — 잠깐 쉬어가세요");
                    nextBreak += breakMin * 60_000L;
                }
                // 게임을 떠나 다른 앱(게임부스터 제외)으로 간 지 LEAVE_MS 지나면 종료
                if (canSee && fgPkg != null && sessionPkg != null) {
                    boolean inGame = fgPkg.equals(sessionPkg);
                    boolean inMe = fgPkg.equals(getPackageName());
                    if (inGame || inMe) leftAt = 0;
                    else if (leftAt == 0) leftAt = now;
                    else if (now - leftAt >= LEAVE_MS) { endSession(false, true); return; }
                }
                if (now - start > MAX_MS) { endSession(false, true); return; }
                h.postDelayed(this, HUD_MS);
                return;
            }

            if (watching) {
                // 등록한 게임이 앞에 나오면 세션 자동 시작
                if (fgPkg != null && isMyGame(fgPkg)) { autoStart(fgPkg); return; }
                h.postDelayed(this, 3_000);
                return;
            }
            stopAll();
        }
    };

    /** 게임 중 실시간 경고: 45℃ 이상, 배터리 15% 이하 (HUD가 꺼져 있어도 알림) */
    private void checkAlerts() {
        checkThermalAlert();
        if (!prefs(this).getBoolean("alerts", true)) return;
        try {
            Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bat == null) return;
            double temp = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0;
            int lvl = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / Math.max(1, bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
            int st = bat.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;

            if (temp > 0 && temp < HEAT_REARM) heatArmed = true;
            if (temp >= HEAT_ALERT && heatArmed) {
                heatArmed = false;
                alert(91, "🔥 휴대폰이 뜨거워요 (" + String.format(java.util.Locale.US, "%.1f", temp) + "℃)",
                        charging ? "충전기를 빼거나 잠깐 쉬어가세요. 이대로면 성능이 떨어지고 배터리에 무리가 가요."
                                 : "잠깐 쉬거나 그래픽을 낮춰주세요. 이대로면 휴대폰이 스스로 성능을 낮춰요.");
                if (hud != null) hud.flash("과열 " + String.format(java.util.Locale.US, "%.0f", temp) + "℃ — 잠깐 쉬어가세요");
            }
            if (charging || lvl > BAT_ALERT + 5) batArmed = true;
            if (!charging && lvl <= BAT_ALERT && batArmed) {
                batArmed = false;
                alert(92, "🔋 배터리 " + lvl + "% 남았어요", "게임이 갑자기 꺼지기 전에 충전기를 연결하세요.");
                if (hud != null) hud.flash("배터리 " + lvl + "% — 충전하세요");
            }
        } catch (Exception ignored) {}
    }

    /** 성능 제한 알림: 휴대폰이 발열로 성능을 낮출 단계가 되면 한 번 알림 (사용자가 켰을 때만) */
    private void checkThermalAlert() {
        if (Build.VERSION.SDK_INT < 29 || !prefs(this).getBoolean("thermalAlert", false)) return;
        try {
            int st = ((PowerManager) getSystemService(POWER_SERVICE)).getCurrentThermalStatus();
            if (st < PowerManager.THERMAL_STATUS_MODERATE) thermalArmed = true;
            if (st >= PowerManager.THERMAL_STATUS_SEVERE && thermalArmed) {
                thermalArmed = false;
                alert(93, "발열로 성능이 낮아질 수 있어요", "잠깐 쉬거나 게임 그래픽을 낮춰 보세요.");
                if (hud != null) hud.flash("발열로 성능 저하 가능 — 잠깐 쉬어가세요");
            }
        } catch (Exception ignored) {}
    }

    private void alert(int id, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel("alert", "게임 중 경고", NotificationManager.IMPORTANCE_HIGH);
            ch.setBypassDnd(true);
            nm.createNotificationChannel(ch);
            nm.notify(id, new Notification.Builder(this, "alert")
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setCategory(Notification.CATEGORY_ALARM)   // 방해금지(중요 알림만) 중에도 보이도록
                    .setAutoCancel(true)
                    .setTimeoutAfter(120_000)
                    .build());
        } catch (Exception ignored) {}
    }

    /** 사용 기록으로 지금 맨 앞 앱을 갱신 */
    private void updateForeground() {
        try {
            UsageStatsManager um = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            UsageEvents ev = um.queryEvents(lastQuery > 0 ? lastQuery : now - 10_000, now);   // 처음엔 전체 기록을 읽지 않음
            UsageEvents.Event e = new UsageEvents.Event();
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) fgPkg = e.getPackageName();
            }
            lastQuery = now;
        } catch (Exception ignored) {}
    }

    private boolean isMyGame(String pkg) {
        java.util.Set<String> g = prefs(this).getStringSet("games", null);
        return g != null && g.contains(pkg);
    }

    private void autoStart(String pkg) {
        String label = pkg;
        try { label = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception ignored) {}
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        if (prepare(this, pkg, label, false, mi.availMem, mi.availMem, prefs(this).getBoolean("record", true))) startSessionUi();
        else h.postDelayed(tick, 3_000);
    }

    /** 세션 종료. open: 게임부스터 열기, notify: 리포트 알림 */
    private void endSession(boolean open, boolean notify) {
        closeSessionUi();
        JSONObject rep = finish(this); // 리포트 저장 (pendingReport 설정) + 설정 복원
        if (rep != null && notify && !open) reportNoti(rep);
        if (open) {
            try {
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            } catch (Exception ignored) {}
        }
        afterSession();
    }

    private void closeSessionUi() {
        inSession = false;
        sessionPkg = null;
        h.removeCallbacks(tick);
        if (hud != null) { hud.hide(); hud = null; }
        if (keepView != null) {
            try { ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).removeView(keepView); } catch (Exception ignored) {}
            keepView = null;
        }
    }

    /** 세션이 끝난 뒤: 감시 모드면 계속 감시, 아니면 멈춤 */
    private void afterSession() {
        if (watchEnabled(this)) {
            fgPkg = getPackageName();   // 방금 끝난 게임을 바로 다시 잡지 않도록
            if (!goForeground(watchNoti())) { stopAll(); return; }
            h.postDelayed(tick, 3_000);
        } else stopAll();
    }

    private void stopAll() {
        closeSessionUi();
        h.removeCallbacksAndMessages(null);
        stopForeground(true);
        stopSelf();
    }

    // ---------- 알림 ----------

    private PendingIntent openApp(int req) {
        return PendingIntent.getActivity(this, req,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNoti() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH, "게임 기록", NotificationManager.IMPORTANCE_LOW));
        String label = "게임";
        try { label = new JSONObject(readAll(metaFile(this))).optString("label", "게임"); } catch (Exception ignored) {}
        PendingIntent stop = PendingIntent.getService(this, 3,
                new Intent(this, SessionService.class).setAction(ACTION_STOP).putExtra("open", true),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean rec = recording();
        Notification.Builder nb = new Notification.Builder(this, CH);
        if (hudClosed && prefs(this).getBoolean("hud", false)) {
            PendingIntent showHud = PendingIntent.getService(this, 6,
                    new Intent(this, SessionService.class).setAction(ACTION_SHOW_HUD),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            nb.addAction(new Notification.Action.Builder(null, "HUD 다시 보기", showHud).build());
        }
        return nb
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle(label + (rec ? " 기록 중" : " 게임 모드 켜짐"))
                .setContentText(hasUsageAccess(this)
                        ? (rec ? "게임을 나가고 잠시 뒤 자동으로 끝나고 리포트가 나와요" : "게임을 나가고 잠시 뒤 설정이 원래대로 돌아와요")
                        : "끝나면 게임부스터로 돌아오거나 여기서 끝내주세요")
                .setOngoing(true)
                .setContentIntent(openApp(2))
                .addAction(new Notification.Action.Builder(null, rec ? "기록 끝내기" : "게임 모드 끝내기", stop).build())
                .build();
    }

    private Notification watchNoti() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH_WATCH, "게임 자동 감지", NotificationManager.IMPORTANCE_MIN));
        return new Notification.Builder(this, CH_WATCH)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("게임 자동 감지 중")
                .setContentText("등록한 게임을 켜면 게임 모드·HUD·기록이 자동으로 시작돼요")
                .setOngoing(true)
                .setContentIntent(openApp(4))
                .build();
    }

    private void reportNoti(JSONObject rep) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CH_REPORT, "게임 리포트", NotificationManager.IMPORTANCE_DEFAULT));
            long d = rep.optLong("duration");
            String dur = d >= 3600 ? (d / 3600) + "시간 " + (d / 60 % 60) + "분" : (d / 60) + "분";
            Notification n = new Notification.Builder(this, CH_REPORT)
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setContentTitle(rep.optString("label") + " 리포트가 준비됐어요")
                    .setContentText(dur + " · 최고 " + String.format(java.util.Locale.US, "%.1f", rep.optDouble("tMax")) + "℃ · 눌러서 보기")
                    .setContentIntent(openApp(5))
                    .setAutoCancel(true)
                    .build();
            nm.notify(99, n);
        } catch (Exception ignored) {}
    }

    private boolean recording() {
        try { return new JSONObject(readAll(metaFile(this))).optBoolean("record", true); } catch (Exception e) { return true; }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() { h.removeCallbacksAndMessages(null); closeSessionUi(); super.onDestroy(); }

    /** 1픽셀 투명 창으로 화면이 꺼지지 않게 유지 */
    private void addKeepScreen() {
        try {
            android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
            keepView = new android.view.View(this);
            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(1, 1,
                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    android.graphics.PixelFormat.TRANSPARENT);
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            wm.addView(keepView, lp);
        } catch (Exception e) { keepView = null; }
    }

    // ---------- 기록 ----------

    static void sample(Context c) {
        try {
            long st = readStart(c);
            Intent bat = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bat == null) return;
            int lvl = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / Math.max(1, bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
            double temp = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0;
            int s = bat.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int charging = (s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL) ? 1 : 0;
            ActivityManager am = (ActivityManager) c.getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            int thermal = 0;
            if (Build.VERSION.SDK_INT >= 29) thermal = ((PowerManager) c.getSystemService(POWER_SERVICE)).getCurrentThermalStatus();
            long t = (System.currentTimeMillis() - st) / 1000;
            try (FileWriter w = new FileWriter(dataFile(c), true)) {
                w.write(t + "," + temp + "," + lvl + "," + charging + "," + (mi.availMem / 1048576) + "," + thermal + "\n");
            }
        } catch (Exception ignored) {}
    }

    private static long readStart(Context c) {
        try { return new JSONObject(readAll(metaFile(c))).getLong("start"); }
        catch (Exception e) { return System.currentTimeMillis(); }
    }

    static String readAll(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String l; while ((l = br.readLine()) != null) sb.append(l).append('\n');
        }
        return sb.toString();
    }

    /**
     * 기록을 끝내고 리포트를 만든다. 리포트(JSON) 또는 너무 짧으면 null.
     * 앱으로 돌아왔을 때(MainActivity) 또는 알림에서 끝낼 때 부른다.
     */
    static synchronized JSONObject finish(Context c) {
        lastShort = false;
        GameMode.restore(c);   // 방해금지·볼륨 원래대로
        boolean adRan = AdBlockVpn.running;
        int adsBlocked = adRan ? AdBlockVpn.blocked.get() : -1;
        int adQueries = adRan ? AdBlockVpn.queries.get() : 0;
        JSONArray adSeen = new JSONArray();
        if (adRan) {
            List<java.util.Map.Entry<String, int[]>> es = new ArrayList<>(AdBlockVpn.seen.entrySet());
            java.util.Collections.sort(es, (x, y) -> (y.getValue()[1] - x.getValue()[1]) != 0 ? y.getValue()[1] - x.getValue()[1] : y.getValue()[0] - x.getValue()[0]);
            for (int i = 0; i < es.size() && i < 60; i++) {
                try { adSeen.put(new JSONObject().put("d", es.get(i).getKey()).put("n", es.get(i).getValue()[0]).put("b", es.get(i).getValue()[1] == 1)); }
                catch (Exception ignored) {}
            }
        }
        AdBlockVpn.stop(c);    // 광고 차단 해제
        File mf = metaFile(c);
        if (!mf.exists()) return null;
        try {
            sample(c); // 마지막 값
            JSONObject meta = new JSONObject(readAll(mf));
            if (!meta.optBoolean("record", true)) { mf.delete(); dataFile(c).delete(); return null; }
            List<double[]> rows = new ArrayList<>();
            File df = dataFile(c);
            if (df.exists()) for (String line : readAll(df).split("\n")) {
                String[] p = line.trim().split(",");
                if (p.length < 6) continue;
                try {   // 강제 종료로 잘린 줄은 건너뜀
                    double[] r = new double[6];
                    for (int i = 0; i < 6; i++) r[i] = Double.parseDouble(p[i]);
                    rows.add(r);
                } catch (NumberFormatException ignored) {}
            }
            mf.delete();
            df.delete();
            long dur = (System.currentTimeMillis() - meta.getLong("start")) / 1000;
            if (dur < MIN_SEC || rows.size() < 2) { lastShort = true; return null; }

            double tMax = -99, tSum = 0; int hot = 0, thMax = 0; long ramSum = 0; boolean charged = false; int chargeRows = 0; long thFrom = -1, thTo = -1;
            for (double[] r : rows) {
                tMax = Math.max(tMax, r[1]); tSum += r[1];
                if (r[1] >= 42) hot++;
                thMax = Math.max(thMax, (int) r[5]);
                if (r[5] >= 3) { if (thFrom < 0) thFrom = (long) r[0]; thTo = (long) r[0]; }
                ramSum += (long) r[4];
                if (r[3] > 0) { charged = true; chargeRows++; }
            }
            double[] first = rows.get(0), last = rows.get(rows.size() - 1);
            int used = (int) (first[2] - last[2]);

            JSONObject rep = new JSONObject();
            rep.put("id", meta.getLong("start"));
            rep.put("pkg", meta.optString("pkg"));
            rep.put("label", meta.optString("label"));
            rep.put("ver", meta.optString("ver"));
            rep.put("start", meta.getLong("start"));
            rep.put("duration", dur);
            rep.put("boosted", meta.optBoolean("boosted"));
            if (meta.optBoolean("adBlock") && adsBlocked >= 0) {
                rep.put("adsBlocked", adsBlocked);
                rep.put("adQueries", adQueries);
                rep.put("adSeen", adSeen);
            }
            if (meta.optBoolean("adWanted") && !adRan)
                rep.put("adBlockFail", AdBlockVpn.revoked ? "revoked" : meta.optBoolean("adBlock") ? "notStarted" : "noPermission");
            rep.put("freedMb", Math.max(0, (meta.optLong("ramAfter") - meta.optLong("ramBefore")) / 1048576));
            rep.put("batStart", (int) first[2]);
            rep.put("batEnd", (int) last[2]);
            rep.put("batUsed", used);
            rep.put("perHour", (!charged && dur >= DRAIN_MIN_SEC && used >= DRAIN_MIN_USED) ? Math.round(used * 3600.0 / dur * 10) / 10.0 : -1);
            rep.put("charged", charged);
            rep.put("chargedPart", charged && chargeRows < rows.size());   // 일부 구간만 충전
            rep.put("tStart", first[1]);
            rep.put("tEnd", last[1]);
            rep.put("tMax", tMax);
            rep.put("tAvg", Math.round(tSum / rows.size() * 10) / 10.0);
            rep.put("hotMin", Math.round(hot * INTERVAL_MS / 60000.0 * 10) / 10.0);
            rep.put("thermalMax", thMax);
            rep.put("thFrom", thFrom);   // 성능 제한 구간(초), 없으면 -1
            rep.put("thTo", thTo);
            rep.put("ramAvgMb", ramSum / rows.size());

            // 그래프용 (최대 120점)
            JSONArray ts = new JSONArray(), temps = new JSONArray(), bats = new JSONArray();
            int step = Math.max(1, rows.size() / 120);
            for (int i = 0; i < rows.size(); i += step) {
                ts.put((long) rows.get(i)[0]); temps.put(rows.get(i)[1]); bats.put((int) rows.get(i)[2]);
            }
            if ((rows.size() - 1) % step != 0) { ts.put((long) last[0]); temps.put(last[1]); bats.put((int) last[2]); }
            rep.put("t", ts); rep.put("temp", temps); rep.put("bat", bats);

            // 저장: 게임당 최근 20판, 전체 60판까지
            android.content.SharedPreferences prefs = c.getSharedPreferences("gb", MODE_PRIVATE);
            JSONArray all = new JSONArray(prefs.getString("sessions", "[]"));
            JSONArray out = new JSONArray();
            out.put(rep);
            java.util.Map<String, Integer> perGame = new java.util.HashMap<>();
            perGame.put(rep.optString("pkg"), 1);
            for (int i = 0; i < all.length() && out.length() < 60; i++) {
                JSONObject o = all.getJSONObject(i);
                int n = perGame.getOrDefault(o.optString("pkg"), 0);
                if (n >= 20) continue;
                perGame.put(o.optString("pkg"), n + 1);
                out.put(o);
            }
            prefs.edit().putString("sessions", out.toString()).putLong("pendingReport", rep.getLong("id")).apply();
            return rep;
        } catch (Exception e) {
            mf.delete();
            dataFile(c).delete();
            return null;
        }
    }
}
