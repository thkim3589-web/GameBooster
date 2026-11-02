package com.thkim.gamebooster;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 게임 전용 광고 차단.
 * - 지정한 게임 앱 하나만 이 VPN을 거친다 (카톡·문자·전화 등 다른 앱은 전혀 거치지 않음)
 * - 그 게임에서도 DNS 조회(주소 찾기)만 받는다. 게임 데이터는 VPN을 거치지 않고 원래대로 나간다
 * - 광고 도메인은 '없는 주소'로 답하고, 나머지는 원래 DNS 서버에 물어서 그대로 전달한다
 * - 게임 세션이 끝나거나 사라지면 스스로 꺼진다
 */
public class AdBlockVpn extends VpnService {

    static final String ACTION_START = "com.thkim.gamebooster.ADBLOCK_START";
    static final String ACTION_STOP = "com.thkim.gamebooster.ADBLOCK_STOP";
    private static final String TUN_ADDR = "10.215.173.1";
    private static final String FAKE_DNS = "10.215.173.2";

    static final AtomicInteger blocked = new AtomicInteger();
    static final AtomicInteger queries = new AtomicInteger();
    /** 진단용: 이번 판에 게임이 조회한 주소 → [횟수, 차단여부] */
    static final java.util.concurrent.ConcurrentHashMap<String, int[]> seen = new java.util.concurrent.ConcurrentHashMap<>();
    static volatile boolean running;

    private ParcelFileDescriptor tun;
    private Thread loop;
    private ExecutorService pool;
    private final List<InetAddress> upstream = new ArrayList<>();

    static void loadCustom(Context c) {
        java.util.Set<String> s = c.getSharedPreferences("gb", MODE_PRIVATE).getStringSet("customBlock", new java.util.HashSet<>());
        AdFilter.custom = s.toArray(new String[0]);
    }

    /** 사용자가 VPN 사용을 허용했는지 */
    static boolean permitted(Context c) {
        try { return VpnService.prepare(c) == null; } catch (Exception e) { return false; }
    }

    static void start(Context c, String pkg) {
        if (!permitted(c)) return;
        try { c.startService(new Intent(c, AdBlockVpn.class).setAction(ACTION_START).putExtra("pkg", pkg)); }
        catch (Exception ignored) {}
    }

    static void stop(Context c) {
        if (!running) return;
        try { c.startService(new Intent(c, AdBlockVpn.class).setAction(ACTION_STOP)); } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            String pkg = intent.getStringExtra("pkg");
            shutdown();
            if (pkg != null && establish(pkg)) return START_NOT_STICKY;
        }
        shutdown();
        stopSelf();
        return START_NOT_STICKY;
    }

    private boolean establish(String pkg) {
        try {
            // VPN을 켜기 전에 원래 DNS 서버를 알아둔다
            upstream.clear();
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
            if (lp != null) for (InetAddress a : lp.getDnsServers()) if (a instanceof Inet4Address) upstream.add(a);
            upstream.add(InetAddress.getByName("1.1.1.1"));
            upstream.add(InetAddress.getByName("8.8.8.8"));

            Builder b = new Builder()
                    .setSession("게임부스터 광고 차단")
                    .addAddress(TUN_ADDR, 24)
                    .addDnsServer(FAKE_DNS)
                    .addRoute(FAKE_DNS, 32)          // DNS만 VPN으로, 나머지는 원래 길로
                    .setBlocking(true);
            b.addAllowedApplication(pkg);            // 이 게임만
            tun = b.establish();
            if (tun == null) return false;
            loadCustom(this);
            blocked.set(0);
            queries.set(0);
            seen.clear();
            running = true;
            pool = Executors.newFixedThreadPool(4);
            loop = new Thread(this::readLoop, "adblock");
            loop.start();
            // 안전 장치: 게임 세션이 사라지면(앱 강제 종료 등) 30초 안에 스스로 꺼짐
            new Thread(() -> {
                while (running) {
                    try { Thread.sleep(30_000); } catch (InterruptedException e) { return; }
                    if (running && !SessionService.isRecording(this)) { shutdown(); stopSelf(); return; }
                }
            }, "adblock-watch").start();
            return true;
        } catch (Exception e) {
            shutdown();
            return false;
        }
    }

    private void readLoop() {
        try (FileInputStream in = new FileInputStream(tun.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(tun.getFileDescriptor())) {
            byte[] buf = new byte[32767];
            while (running && !Thread.interrupted()) {
                int len = in.read(buf);
                if (len <= 0) continue;
                final byte[] pkt = new byte[len];
                System.arraycopy(buf, 0, pkt, 0, len);
                ExecutorService p = pool;
                if (p != null) p.execute(() -> handle(pkt, out));
            }
        } catch (Exception ignored) {}
    }

    private void handle(byte[] pkt, FileOutputStream out) {
        int off = AdFilter.dnsPayloadOffset(pkt, pkt.length);
        if (off < 0) return;
        int dlen = pkt.length - off;
        byte[] answer;
        String name = AdFilter.queryName(pkt, off, dlen);
        boolean isAd = AdFilter.isBlocked(name);
        queries.incrementAndGet();
        if (name != null && seen.size() < 300) {
            int[] v = seen.computeIfAbsent(name.toLowerCase(java.util.Locale.ROOT), k -> new int[]{0, isAd ? 1 : 0});
            synchronized (v) { v[0]++; }
        }
        if (isAd) {
            answer = AdFilter.blockedResponse(pkt, off, dlen);
            blocked.incrementAndGet();
        } else {
            answer = forward(pkt, off, dlen);
        }
        if (answer == null) return;
        byte[] reply = AdFilter.buildReply(pkt, answer, answer.length);
        synchronized (this) {
            try { out.write(reply); } catch (Exception ignored) {}
        }
    }

    /** 원래 DNS 서버에 물어서 답을 받아온다 (VPN을 다시 타지 않도록 protect) */
    private byte[] forward(byte[] pkt, int off, int dlen) {
        for (InetAddress server : upstream) {
            try (DatagramSocket s = new DatagramSocket()) {
                protect(s);
                s.setSoTimeout(2500);
                s.send(new DatagramPacket(pkt, off, dlen, server, 53));
                byte[] rb = new byte[4096];
                DatagramPacket rp = new DatagramPacket(rb, rb.length);
                s.receive(rp);
                byte[] r = new byte[rp.getLength()];
                System.arraycopy(rb, 0, r, 0, r.length);
                return r;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void shutdown() {
        running = false;
        if (loop != null) { loop.interrupt(); loop = null; }
        if (pool != null) { pool.shutdownNow(); pool = null; }
        if (tun != null) { try { tun.close(); } catch (Exception ignored) {} tun = null; }
    }

    @Override
    public void onRevoke() {      // 다른 VPN이 켜지거나 사용자가 끊음
        shutdown();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }
}
