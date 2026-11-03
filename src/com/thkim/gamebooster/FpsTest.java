package com.thkim.gamebooster;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * FPS 측정 시험 (선택 기능). Shizuku로 받은 셸 권한으로 SurfaceFlinger의 프레임 표시 시각을 읽는다.
 * 값을 읽지 못하면 추정하지 않고 '측정 불가'와 진단 내용을 남긴다.
 */
final class FpsTest {
    static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";
    private static final long LOAD_WAIT_MS = 15_000, CAPTURE_MS = 30_000, TICK_MS = 1_000;
    private static volatile boolean running, stop;

    private FpsTest() {}

    static boolean isRunning() { return running; }

    static JSONObject status(Context c) throws Exception {
        JSONObject o = new JSONObject();
        boolean installed = false;
        try { c.getPackageManager().getPackageInfo(SHIZUKU_PKG, 0); installed = true; } catch (Exception ignored) {}
        boolean alive = false, allowed = false, old = false;
        try {
            alive = Shizuku.pingBinder();
            if (alive) {
                old = Shizuku.isPreV11();
                allowed = !old && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            }
        } catch (Throwable ignored) {}
        o.put("installed", installed);
        o.put("alive", alive);
        o.put("old", old);
        o.put("allowed", allowed);
        o.put("running", running);
        return o;
    }

    static void requestPermission() { Shizuku.requestPermission(7301); }

    static void stop() { stop = true; }

    static boolean start(Context ctx, String pkg, String label) {
        if (running) return false;
        running = true;
        stop = false;
        final Context c = ctx.getApplicationContext();
        new Thread(() -> {
            JSONObject res = new JSONObject();
            try { measure(pkg, label, res); }
            catch (Throwable e) { put(res, "error", String.valueOf(e)); }
            put(res, "time", System.currentTimeMillis());
            c.getSharedPreferences("gb", Context.MODE_PRIVATE).edit().putString("fpsLast", res.toString()).apply();
            running = false;
            notifyDone(c, label);
        }, "fps-test").start();
        return true;
    }

    private static void measure(String pkg, String label, JSONObject res) throws Exception {
        put(res, "pkg", pkg);
        put(res, "label", label);
        IShizukuService svc = IShizukuService.Stub.asInterface(Shizuku.getBinder());
        if (svc == null) throw new IllegalStateException("Shizuku 연결 없음");

        // 게임이 뜰 때까지 기다린 뒤, 후보 레이어를 동시에 읽어 프레임이 가장 많은 쪽을 고른다
        sleep(LOAD_WAIT_MS);
        Map<String, TreeSet<Long>> frames = new HashMap<>();
        List<String> seen = new ArrayList<>();
        String sample = null;
        // 진단용: 화면 전체 갱신 횟수 (값을 표시하지는 않고, 다른 측정 방법을 판단할 때만 씀)
        long flip0 = flips(svc), flipT0 = System.currentTimeMillis();
        long period = 0, end = System.currentTimeMillis() + CAPTURE_MS, nextList = 0;
        List<String> cand = new ArrayList<>();
        while (!stop && System.currentTimeMillis() < end) {
            if (System.currentTimeMillis() >= nextList) {
                cand = FpsCalc.candidates(run(svc, new String[]{"dumpsys", "SurfaceFlinger", "--list"}), pkg, 4);
                nextList = System.currentTimeMillis() + 5_000;
                for (String l : cand) if (!seen.contains(l)) seen.add(l);
            }
            for (String l : cand) {
                TreeSet<Long> set = frames.get(l);
                if (set == null) { set = new TreeSet<>(); frames.put(l, set); }
                String out = run(svc, new String[]{"dumpsys", "SurfaceFlinger", "--latency", l});
                long p = FpsCalc.parseLatency(out, set);
                if (set.isEmpty()) {   // 기기에 따라 #번호 없는 이름으로만 조회됨
                    String alt = FpsCalc.withoutId(l);
                    if (!alt.equals(l)) {
                        out = run(svc, new String[]{"dumpsys", "SurfaceFlinger", "--latency", alt});
                        long p2 = FpsCalc.parseLatency(out, set);
                        if (p2 > 0) p = p2;
                    }
                }
                if (p > 0) period = p;
                if (sample == null || (out != null && out.split("\n").length > sample.split("\n").length)) sample = out;
            }
            sleep(TICK_MS);
        }
        String best = null; int most = 0;
        JSONArray diag = new JSONArray();
        for (String l : seen) {
            TreeSet<Long> s = frames.get(l);
            int n = s == null ? 0 : s.size();
            diag.put(l + " : " + n + "프레임");
            if (n > most) { most = n; best = l; }
        }
        put(res, "layers", diag);
        long flip1 = flips(svc), flipSec = (System.currentTimeMillis() - flipT0) / 1000;
        if (flip0 >= 0 && flip1 > flip0 && flipSec > 0) put(res, "flipRate", (flip1 - flip0) / flipSec);
        if (sample != null) {
            String[] ls = sample.split("\n");
            StringBuilder sb = new StringBuilder("응답 " + ls.length + "줄");
            for (int i = 0; i < ls.length && i < 3; i++) sb.append(" | ").append(ls[i].trim());
            put(res, "sample", sb.toString());
        }
        if (period > 0) put(res, "refreshHz", Math.round(1e9 / period));
        FpsCalc.Result r = best == null ? new FpsCalc.Result() : FpsCalc.compute(frames.get(best));
        put(res, "frames", r.frames);
        put(res, "ok", r.ok);
        if (r.ok) {
            put(res, "layer", best);
            put(res, "avgFps", Math.round(r.avgFps * 10) / 10.0);
            put(res, "low1Fps", Math.round(r.low1Fps * 10) / 10.0);
            put(res, "stutters", r.stutters);
            put(res, "seconds", Math.round(r.seconds));
        }
    }

    /** SurfaceFlinger의 화면 갱신 누적 횟수 (못 읽으면 -1) */
    private static long flips(IShizukuService svc) {
        try {
            String o = run(svc, new String[]{"service", "call", "SurfaceFlinger", "1013"});
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Parcel\\(([0-9a-fA-F]{8})").matcher(o);
            return m.find() ? Long.parseLong(m.group(1), 16) : -1;
        } catch (Throwable e) { return -1; }
    }

    /** 셸 명령 실행 (4초 안에 끝나지 않으면 중단) */
    private static String run(IShizukuService svc, String[] cmd) throws Exception {
        IRemoteProcess p = svc.newProcess(cmd, null, null);
        final String[] out = {""};
        final ParcelFileDescriptor fd = p.getInputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) > 0) bos.write(b, 0, n);
                out[0] = bos.toString("UTF-8");
            } catch (Exception ignored) {}
        });
        reader.start();
        reader.join(4_000);
        try { p.destroy(); } catch (Exception ignored) {}
        return out[0];
    }

    private static void sleep(long ms) {
        long until = System.currentTimeMillis() + ms;
        while (!stop && System.currentTimeMillis() < until) {
            try { Thread.sleep(Math.min(200, ms)); } catch (InterruptedException e) { return; }
        }
    }

    private static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (Exception ignored) {}
    }

    private static void notifyDone(Context c, String label) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("fps", "FPS 측정 시험", NotificationManager.IMPORTANCE_LOW));
            nm.notify(94, new Notification.Builder(c, "fps")
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setContentTitle(label + " FPS 측정이 끝났어요")
                    .setContentText("게임부스터 도구 탭에서 결과를 볼 수 있어요")
                    .setAutoCancel(true)
                    .setTimeoutAfter(120_000)
                    .build());
        } catch (Exception ignored) {}
    }
}
