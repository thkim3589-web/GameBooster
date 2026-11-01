package com.thkim.gamebooster;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 와이파이와 모바일 데이터(4G/5G)를 동시에 잡아서, 같은 서버로 번갈아 지연시간을 잰다.
 * 측정만 하고 연결을 바꾸지는 않는다 (안드로이드가 앱에 허용하지 않음).
 */
public class NetCompare {

    private static final String[] HOSTS = {"8.8.8.8", "1.1.1.1", "168.126.63.1"};
    private static final int PORT = 53;
    private static final int SAMPLES = 10;
    private static final int TIMEOUT = 1500;

    /** 해당 종류의 네트워크를 요청해서 받아온다 (없으면 null) */
    private static Network acquire(ConnectivityManager cm, int transport, ConnectivityManager.NetworkCallback[] holder) {
        final Network[] got = {null};
        final CountDownLatch latch = new CountDownLatch(1);
        ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { got[0] = n; latch.countDown(); }
            @Override public void onUnavailable() { latch.countDown(); }
        };
        holder[0] = cb;
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(transport)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        try {
            cm.requestNetwork(req, cb, 6000);
            latch.await(7, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        return got[0];
    }

    private static long probe(Network n, String host) {
        long t0 = System.nanoTime();
        try (Socket s = new Socket()) {
            n.bindSocket(s);
            s.connect(new InetSocketAddress(host, PORT), TIMEOUT);
            return (System.nanoTime() - t0) / 1_000_000;
        } catch (Exception e) { return -1; }
    }

    /** 두 네트워크 모두 응답하는 서버를 고른다 */
    private static String pickHost(Network a, Network b) {
        for (String h : HOSTS) {
            boolean okA = a == null || probe(a, h) >= 0;
            boolean okB = b == null || probe(b, h) >= 0;
            if (okA && okB) return h;
        }
        return HOSTS[0];
    }

    private static JSONObject stats(long[] v) throws Exception {
        JSONObject o = new JSONObject();
        JSONArray arr = new JSONArray();
        long sum = 0, min = Long.MAX_VALUE, max = 0; int ok = 0;
        for (long x : v) {
            arr.put(x);
            if (x < 0) continue;
            ok++; sum += x; min = Math.min(min, x); max = Math.max(max, x);
        }
        o.put("samples", arr);
        o.put("ok", ok);
        o.put("lost", v.length - ok);
        if (ok == 0) { o.put("avg", -1); o.put("jitter", -1); o.put("min", -1); return o; }
        long avg = sum / ok;
        // 흔들림: 연속 측정값 차이의 평균 (게임에서 '튀는' 느낌과 가장 가까움)
        long jsum = 0; int jn = 0; long prev = -1;
        for (long x : v) { if (x < 0) continue; if (prev >= 0) { jsum += Math.abs(x - prev); jn++; } prev = x; }
        o.put("avg", avg);
        o.put("min", min);
        o.put("max", max);
        o.put("jitter", jn == 0 ? 0 : jsum / jn);
        return o;
    }

    /** 게임용 점수 (낮을수록 좋음): 평균 + 흔들림×2 + 끊김 1회당 150 */
    private static double score(JSONObject s) {
        if (s == null || s.optInt("ok") == 0) return Double.MAX_VALUE;
        return s.optLong("avg") + s.optLong("jitter") * 2.0 + s.optInt("lost") * 150.0;
    }

    public static JSONObject run(Context c) {
        JSONObject r = new JSONObject();
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        ConnectivityManager.NetworkCallback[] wcb = {null}, ccb = {null};
        try {
            // 지금 기본으로 쓰는 연결
            String current = "none";
            Network act = cm.getActiveNetwork();
            NetworkCapabilities ac = act == null ? null : cm.getNetworkCapabilities(act);
            if (ac != null) current = ac.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi"
                    : ac.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "cell" : "other";
            r.put("current", current);

            // 두 네트워크를 동시에 요청 (없는 쪽은 최대 6초 기다림)
            final Network[] got = new Network[2];
            Thread tw = new Thread(() -> got[0] = acquire(cm, NetworkCapabilities.TRANSPORT_WIFI, wcb));
            Thread tc = new Thread(() -> got[1] = acquire(cm, NetworkCapabilities.TRANSPORT_CELLULAR, ccb));
            tw.start(); tc.start(); tw.join(); tc.join();
            Network wifi = got[0], cell = got[1];
            r.put("hasWifi", wifi != null);
            r.put("hasCell", cell != null);
            if (wifi == null && cell == null) { r.put("error", "인터넷에 연결되어 있지 않아요"); return r; }

            String host = pickHost(wifi, cell);
            r.put("host", host);
            long[] w = new long[SAMPLES], m = new long[SAMPLES];
            for (int i = 0; i < SAMPLES; i++) {   // 번갈아 측정해서 같은 조건으로 비교
                w[i] = wifi == null ? -1 : probe(wifi, host);
                m[i] = cell == null ? -1 : probe(cell, host);
                Thread.sleep(150);
            }
            JSONObject ws = wifi == null ? null : stats(w);
            JSONObject ms = cell == null ? null : stats(m);
            if (ws != null) r.put("wifi", ws);
            if (ms != null) r.put("cell", ms);

            double sw = score(ws), sm = score(ms);
            String best;
            if (sw == Double.MAX_VALUE && sm == Double.MAX_VALUE) best = "none";
            else if (Math.abs(sw - sm) < 15) best = "same";
            else best = sw < sm ? "wifi" : "cell";
            r.put("best", best);
            r.put("time", System.currentTimeMillis());
            return r;
        } catch (Exception e) {
            try { r.put("error", "측정 중 오류: " + e.getMessage()); } catch (Exception ignored) {}
            return r;
        } finally {
            try { if (wcb[0] != null) cm.unregisterNetworkCallback(wcb[0]); } catch (Exception ignored) {}
            try { if (ccb[0] != null) cm.unregisterNetworkCallback(ccb[0]); } catch (Exception ignored) {}
        }
    }
}
