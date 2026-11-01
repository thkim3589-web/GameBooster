package com.thkim.gamebooster;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.InetSocketAddress;
import java.net.Socket;

/** 게임 위에 떠 있는 실시간 패널. SessionService가 만들고 2초마다 update()를 부른다. */
public class Hud {

    interface Listener { void onFinish(); }

    private final Context c;
    private final WindowManager wm;
    private final SharedPreferences prefs;
    private final Listener listener;
    private final long start;
    private final String label;

    private LinearLayout panel, bubble;
    private TextView tvRam, tvPing, tvHz, tvBat, tvTime, tvTitle, tvWarn, bTemp, bPing;
    private WindowManager.LayoutParams lp;
    private boolean minimized;
    private volatile long ping = -2;     // -2: 측정 전, -1: 실패
    private volatile boolean pinging;
    private int tick;

    static final int CYAN = 0xFF22D3EE, GREEN = 0xFF4ADE80, ORANGE = 0xFFFB923C, RED = 0xFFF87171,
            SUB = 0xFF94A3B8, TEXT = 0xFFF1F5F9, BG = 0xE60B1220;

    static boolean canShow(Context c) { return Settings.canDrawOverlays(c); }

    Hud(Context c, String label, long start, Listener l) {
        this.c = c;
        this.label = label;
        this.start = start;
        this.listener = l;
        this.wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        this.prefs = c.getSharedPreferences("gb", Context.MODE_PRIVATE);
        this.minimized = prefs.getBoolean("hudMin", false);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    private TextView tv(float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout row(String key, TextView val) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(3), 0, dp(3));
        TextView k = tv(14, SUB, false);
        k.setText(key);
        r.addView(k, new LinearLayout.LayoutParams(0, -2, 1));
        val.setGravity(Gravity.END);
        r.addView(val);
        return r;
    }

    private GradientDrawable bg(int fill, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        g.setStroke(dp(1.5f), stroke);
        return g;
    }

    void show() {
        if (!canShow(c) || panel != null) return;
        // 펼친 패널
        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(10), dp(14), dp(12));
        panel.setBackground(bg(BG, CYAN, dp(16)));

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        tvTitle = tv(14, TEXT, true);
        tvTitle.setText("⚡ " + label);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(tvTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView min = tv(20, SUB, true);
        min.setText("  –  ");
        min.setOnClickListener(v -> setMinimized(true));
        head.addView(min);
        panel.addView(head);

        tvWarn = tv(13, RED, true);
        tvWarn.setVisibility(View.GONE);
        panel.addView(tvWarn);

        tvRam = tv(15, TEXT, true); tvPing = tv(15, TEXT, true); tvHz = tv(15, TEXT, true);
        tvBat = tv(15, TEXT, true); tvTime = tv(15, TEXT, true);
        panel.addView(row("여유 메모리", tvRam));
        panel.addView(row("핑", tvPing));
        panel.addView(row("화면", tvHz));
        panel.addView(row("배터리", tvBat));
        panel.addView(row("플레이", tvTime));

        TextView fin = tv(15, 0xFF04151B, true);
        fin.setText("끝내고 리포트 보기");
        fin.setGravity(Gravity.CENTER);
        fin.setPadding(0, dp(9), 0, dp(9));
        fin.setBackground(bg(CYAN, CYAN, dp(10)));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.topMargin = dp(8);
        fin.setOnClickListener(v -> { if (listener != null) listener.onFinish(); });
        panel.addView(fin, flp);

        // 접은 버블
        bubble = new LinearLayout(c);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setGravity(Gravity.CENTER);
        bubble.setBackground(bg(BG, CYAN, dp(32)));
        bTemp = tv(15, TEXT, true);
        bTemp.setGravity(Gravity.CENTER);
        bPing = tv(11, SUB, true);
        bPing.setGravity(Gravity.CENTER);
        bubble.addView(bTemp);
        bubble.addView(bPing);

        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(-2, -2, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        android.util.DisplayMetrics dm0 = c.getResources().getDisplayMetrics();
        lp.x = Math.min(prefs.getInt("hudX", dp(12)), Math.max(0, dm0.widthPixels - dp(240)));
        lp.y = Math.min(prefs.getInt("hudY", dp(80)), Math.max(0, dm0.heightPixels - dp(260)));

        View.OnTouchListener drag = new Drag();
        panel.setOnTouchListener(drag);
        head.setOnTouchListener(drag);
        bubble.setOnTouchListener(drag);

        attach();
        update();
    }

    private void attach() {
        try {
            if (minimized) {
                lp.width = dp(64); lp.height = dp(64);
                wm.addView(bubble, lp);
            } else {
                lp.width = dp(230); lp.height = -2;
                wm.addView(panel, lp);
            }
        } catch (Exception ignored) {}
    }

    private void detachAll() {
        try { if (panel != null && panel.isAttachedToWindow()) wm.removeView(panel); } catch (Exception ignored) {}
        try { if (bubble != null && bubble.isAttachedToWindow()) wm.removeView(bubble); } catch (Exception ignored) {}
    }

    private void setMinimized(boolean m) {
        minimized = m;
        prefs.edit().putBoolean("hudMin", m).apply();
        detachAll();
        attach();
        update();
    }

    void hide() {
        detachAll();
        panel = null;
        bubble = null;
    }

    private long flashUntil;
    private String flashMsg;

    /** 잠깐 띄우는 안내 (휴식 알림 등) */
    void flash(String msg) {
        flashMsg = msg;
        flashUntil = System.currentTimeMillis() + 15_000;
        if (minimized) setMinimized(false);
        update();
    }

    /** 2초마다 호출 */
    void update() {
        if (panel == null) return;
        tick++;
        // 핑: 6초마다 (백그라운드)
        if (tick % 3 == 1 && !pinging) {
            pinging = true;
            new Thread(() -> {
                long t0 = System.nanoTime();
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress("8.8.8.8", 53), 1500);
                    ping = (System.nanoTime() - t0) / 1_000_000;
                } catch (Exception e) { ping = -1; }
                pinging = false;
            }).start();
        }

        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        int freePct = (int) (mi.availMem * 100 / Math.max(1, mi.totalMem));

        Intent bat = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int lvl = 0; double temp = 0;
        if (bat != null) {
            lvl = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / Math.max(1, bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
            temp = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0;
        }
        float hz = 60;
        try { hz = wm.getDefaultDisplay().getRefreshRate(); } catch (Exception ignored) {}
        long sec = (System.currentTimeMillis() - start) / 1000;

        int tColor = temp >= 42 ? RED : temp >= 38 ? ORANGE : GREEN;
        int pColor = ping < 0 ? SUB : ping <= 60 ? GREEN : ping <= 120 ? ORANGE : RED;

        tvRam.setText(String.format(java.util.Locale.US, "%.1fGB (%d%%)", mi.availMem / 1073741824.0, freePct));
        tvRam.setTextColor(freePct < 15 ? RED : freePct < 25 ? ORANGE : TEXT);
        tvPing.setText(ping == -2 ? "측정 중" : ping == -1 ? "끊김" : ping + "ms");
        tvPing.setTextColor(ping == -1 ? RED : pColor);
        tvHz.setText(Math.round(hz) + "Hz");
        tvBat.setText(lvl + "%" + (temp > 0 ? String.format(java.util.Locale.US, " · %.1f℃", temp) : ""));
        tvBat.setTextColor(tColor);
        tvTime.setText(String.format(java.util.Locale.US, "%02d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60));

        boolean hot = temp >= 42;
        boolean fl = System.currentTimeMillis() < flashUntil;
        tvWarn.setVisibility(hot || fl ? View.VISIBLE : View.GONE);
        tvWarn.setText(fl ? flashMsg : "🔥 과열 — 성능이 떨어질 수 있어요");
        tvWarn.setTextColor(fl ? 0xFFFACC15 : RED);
        panel.setBackground(bg(BG, fl ? 0xFFFACC15 : hot ? RED : CYAN, dp(16)));

        bTemp.setText(temp > 0 ? String.format(java.util.Locale.US, "%.0f°", temp) : lvl + "%");
        bTemp.setTextColor(tColor);
        bPing.setText(ping >= 0 ? ping + "ms" : ping == -1 ? "끊김" : "…");
        bubble.setBackground(bg(BG, hot ? RED : CYAN, dp(32)));
    }

    /** 끌어서 옮기기 (조금만 움직이면 탭으로 처리) */
    private class Drag implements View.OnTouchListener {
        int sx, sy; float tx, ty; boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    sx = lp.x; sy = lp.y; tx = e.getRawX(); ty = e.getRawY(); moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (e.getRawX() - tx), dy = (int) (e.getRawY() - ty);
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                    if (moved) {
                        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
                        View cur = minimized ? bubble : panel;
                        int w = cur.getWidth() > 0 ? cur.getWidth() : dp(64), hh = cur.getHeight() > 0 ? cur.getHeight() : dp(64);
                        lp.x = Math.max(0, Math.min(dm.widthPixels - w, sx + dx));
                        lp.y = Math.max(0, Math.min(dm.heightPixels - hh, sy + dy));
                        try { wm.updateViewLayout(minimized ? bubble : panel, lp); } catch (Exception ignored) {}
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (moved) prefs.edit().putInt("hudX", lp.x).putInt("hudY", lp.y).apply();
                    else { v.performClick(); if (minimized && v == bubble) setMinimized(false); }
                    return true;
            }
            return false;
        }
    }
}
