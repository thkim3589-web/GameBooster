package com.thkim.gamebooster;

import android.app.ActivityManager;
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
 * 게임하는 동안 10초마다 온도·배터리·메모리·발열 단계를 기록한다.
 * 게임부스터로 돌아오거나 알림의 '기록 끝내기'를 누르면 끝나고 리포트가 저장된다.
 */
public class SessionService extends Service {

    static final String ACTION_STOP = "com.thkim.gamebooster.SESSION_STOP";
    private static final String CH = "session";
    private static final int NOTI_ID = 77;
    private static final long INTERVAL_MS = 10_000;  // 기록 간격
    private static final long HUD_MS = 2_000;        // HUD 갱신 간격
    private static final long MAX_MS = 4L * 60 * 60 * 1000; // 최대 4시간
    static final long MIN_SEC = 30;                            // 30초 미만은 버림

    private final Handler h = new Handler(Looper.getMainLooper());
    private long start;
    private Hud hud;
    private int ticks;
    private android.view.View keepView;   // 화면 켜짐 유지용 투명 점
    private int breakMin;                 // 휴식 알림 간격(분), 0이면 끔
    private long nextBreak;
    static volatile boolean lastWasRecord = true;

    static File metaFile(Context c) { return new File(c.getFilesDir(), "session_meta.json"); }
    static File dataFile(Context c) { return new File(c.getFilesDir(), "session_data.csv"); }

    /** 게임 실행 직전에 부른다 */
    static void begin(Context c, String pkg, String label, boolean boosted, long ramBefore, long ramAfter, boolean record) {
        try {
            JSONObject mode = GameMode.apply(c, pkg);   // 게임별 모드 적용 (끝나면 복원)
            JSONObject m = new JSONObject();
            m.put("record", record);
            m.put("keepScreen", mode.optBoolean("keepScreen"));
            m.put("breakMin", mode.optInt("breakMin", 0));
            m.put("pkg", pkg);
            m.put("label", label);
            m.put("start", System.currentTimeMillis());
            m.put("boosted", boosted);
            m.put("ramBefore", ramBefore);
            m.put("ramAfter", ramAfter);
            try (FileWriter w = new FileWriter(metaFile(c))) { w.write(m.toString()); }
            dataFile(c).delete();
            Intent i = new Intent(c, SessionService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) {}
    }

    static boolean isRecording(Context c) { return metaFile(c).exists(); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            endAndOpen(intent.getBooleanExtra("open", false));
            return START_NOT_STICKY;
        }
        start = readStart(this);
        startForeground(NOTI_ID, buildNoti());
        h.removeCallbacksAndMessages(null);
        ticks = 0;
        try {
            JSONObject meta = new JSONObject(readAll(metaFile(this)));
            breakMin = meta.optInt("breakMin", 0);
            nextBreak = breakMin > 0 ? start + breakMin * 60_000L : Long.MAX_VALUE;
            if (meta.optBoolean("keepScreen") && keepView == null && Hud.canShow(this)) addKeepScreen();
        } catch (Exception ignored) {}
        if (hud == null && getSharedPreferences("gb", MODE_PRIVATE).getBoolean("hud", false) && Hud.canShow(this)) {
            String label = "게임";
            try { label = new JSONObject(readAll(metaFile(this))).optString("label", "게임"); } catch (Exception ignored) {}
            hud = new Hud(this, label, start, () -> endAndOpen(true));
            // 게임이 뜬 뒤에 보이도록 살짝 늦게
            h.postDelayed(() -> { if (hud != null) hud.show(); }, 1500);
        }
        h.post(tick);
        return START_STICKY;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!metaFile(SessionService.this).exists()) { closeHud(); stopForeground(true); stopSelf(); return; }
            if (ticks % (INTERVAL_MS / HUD_MS) == 0) sample(SessionService.this);
            ticks++;
            if (hud != null) hud.update();
            long now = System.currentTimeMillis();
            if (now >= nextBreak) {
                int played = (int) ((now - start) / 60_000);
                GameMode.breakNotice(SessionService.this, played);
                if (hud != null) hud.flash("⏰ " + played + "분 했어요 — 잠깐 쉬어가세요");
                nextBreak += breakMin * 60_000L;
            }
            if (System.currentTimeMillis() - start > MAX_MS) { endAndOpen(false); return; }
            h.postDelayed(this, HUD_MS);
        }
    };

    private Notification buildNoti() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH, "게임 기록", NotificationManager.IMPORTANCE_LOW));
        String label = "게임";
        try { label = new JSONObject(readAll(metaFile(this))).optString("label", "게임"); } catch (Exception ignored) {}
        PendingIntent open = PendingIntent.getActivity(this, 2,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 3,
                new Intent(this, SessionService.class).setAction(ACTION_STOP).putExtra("open", true),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle(label + (recording() ? " 기록 중" : " 게임 모드 켜짐"))
                .setContentText(recording() ? "온도·배터리를 기록하고 있어요. 게임부스터로 돌아오면 리포트가 나와요"
                        : "게임부스터로 돌아오면 설정이 원래대로 돌아와요")
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, recording() ? "기록 끝내기" : "게임 모드 끝내기", stop).build())
                .build();
    }

    private boolean recording() {
        try { return new JSONObject(readAll(metaFile(this))).optBoolean("record", true); } catch (Exception e) { return true; }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() { h.removeCallbacksAndMessages(null); closeHud(); super.onDestroy(); }

    private void closeHud() {
        if (hud != null) { hud.hide(); hud = null; }
        if (keepView != null) {
            try { ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).removeView(keepView); } catch (Exception ignored) {}
            keepView = null;
        }
    }

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

    /** 기록을 끝내고, open이면 게임부스터를 열어 리포트를 보여준다 */
    private void endAndOpen(boolean open) {
        h.removeCallbacksAndMessages(null);
        closeHud();
        finish(this); // 리포트 저장 (pendingReport 설정)
        stopForeground(true);
        stopSelf();
        if (open) {
            try {
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            } catch (Exception ignored) {}
        }
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
        GameMode.restore(c);   // 방해금지·볼륨 원래대로
        File mf = metaFile(c);
        if (!mf.exists()) return null;
        try {
            sample(c); // 마지막 값
            JSONObject meta = new JSONObject(readAll(mf));
            lastWasRecord = meta.optBoolean("record", true);
            if (!lastWasRecord) { mf.delete(); dataFile(c).delete(); return null; }
            List<double[]> rows = new ArrayList<>();
            File df = dataFile(c);
            if (df.exists()) for (String line : readAll(df).split("\n")) {
                String[] p = line.trim().split(",");
                if (p.length < 6) continue;
                double[] r = new double[6];
                for (int i = 0; i < 6; i++) r[i] = Double.parseDouble(p[i]);
                rows.add(r);
            }
            mf.delete();
            df.delete();
            long dur = (System.currentTimeMillis() - meta.getLong("start")) / 1000;
            if (dur < MIN_SEC || rows.size() < 2) return null;

            double tMax = -99, tSum = 0; int hot = 0, thMax = 0; long ramSum = 0; boolean charged = false;
            for (double[] r : rows) {
                tMax = Math.max(tMax, r[1]); tSum += r[1];
                if (r[1] >= 42) hot++;
                thMax = Math.max(thMax, (int) r[5]);
                ramSum += (long) r[4];
                if (r[3] > 0) charged = true;
            }
            double[] first = rows.get(0), last = rows.get(rows.size() - 1);
            int used = (int) (first[2] - last[2]);

            JSONObject rep = new JSONObject();
            rep.put("id", meta.getLong("start"));
            rep.put("pkg", meta.optString("pkg"));
            rep.put("label", meta.optString("label"));
            rep.put("start", meta.getLong("start"));
            rep.put("duration", dur);
            rep.put("boosted", meta.optBoolean("boosted"));
            rep.put("freedMb", Math.max(0, (meta.optLong("ramAfter") - meta.optLong("ramBefore")) / 1048576));
            rep.put("batStart", (int) first[2]);
            rep.put("batEnd", (int) last[2]);
            rep.put("batUsed", used);
            rep.put("perHour", (!charged && dur >= 300) ? Math.round(used * 3600.0 / dur * 10) / 10.0 : -1);
            rep.put("charged", charged);
            rep.put("tStart", first[1]);
            rep.put("tEnd", last[1]);
            rep.put("tMax", tMax);
            rep.put("tAvg", Math.round(tSum / rows.size() * 10) / 10.0);
            rep.put("hotMin", Math.round(hot * INTERVAL_MS / 60000.0 * 10) / 10.0);
            rep.put("thermalMax", thMax);
            rep.put("ramAvgMb", ramSum / rows.size());

            // 그래프용 (최대 120점)
            JSONArray ts = new JSONArray(), temps = new JSONArray(), bats = new JSONArray();
            int step = Math.max(1, rows.size() / 120);
            for (int i = 0; i < rows.size(); i += step) {
                ts.put((long) rows.get(i)[0]); temps.put(rows.get(i)[1]); bats.put((int) rows.get(i)[2]);
            }
            if ((rows.size() - 1) % step != 0) { ts.put((long) last[0]); temps.put(last[1]); bats.put((int) last[2]); }
            rep.put("t", ts); rep.put("temp", temps); rep.put("bat", bats);

            // 저장 (최근 30개)
            android.content.SharedPreferences prefs = c.getSharedPreferences("gb", MODE_PRIVATE);
            JSONArray all = new JSONArray(prefs.getString("sessions", "[]"));
            JSONArray out = new JSONArray();
            out.put(rep);
            for (int i = 0; i < all.length() && out.length() < 30; i++) out.put(all.get(i));
            prefs.edit().putString("sessions", out.toString()).putLong("pendingReport", rep.getLong("id")).apply();
            return rep;
        } catch (Exception e) {
            mf.delete();
            return null;
        }
    }
}
