package com.thkim.gamebooster;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;

/**
 * 게임 위에 떠 있는 실시간 패널. SessionService가 만들고 2초마다 update()를 부른다.
 * 모양: 세로 패널 / 가로 화면 한 줄 바 / 접은 동그라미.
 * 설정: 크기(hudSize), 배경 투명도(hudAlpha), 자동 접기(hudAutoMin), 가로 한 줄(hudLine)
 */
public class Hud {

    interface Listener { void onFinish(); void onClose(); }

    private final Context c;
    private final WindowManager wm;
    private final SharedPreferences prefs;
    private final Listener listener;
    private final long start;
    private final String label;

    private LinearLayout panel, bar, bubble;
    private View current;
    private TextView tvRam, tvPing, tvHz, tvBat, tvTime, tvWarn, bTemp, bPing;
    private TextView lRam, lPing, lHz, lBat, lTime;
    private WindowManager.LayoutParams lp;
    private boolean minimized, landscape;
    private long collapseAt = Long.MAX_VALUE;
    private volatile long ping = -2;     // -2: 측정 전, -1: 실패
    private volatile boolean pinging;
    private int tick;
    private long flashUntil;
    private String flashMsg;

    private final float scale;       // 크기
    private final int bgColor;       // 배경 (투명도 포함)
    private final boolean autoMin, lineMode;

    static final int CYAN = 0xFF22D3EE, GREEN = 0xFF4ADE80, ORANGE = 0xFFFB923C, RED = 0xFFF87171,
            YELLOW = 0xFFFACC15, SUB = 0xFF94A3B8, TEXT = 0xFFF1F5F9;
    static final long AUTO_MIN_MS = 10_000;

    static boolean canShow(Context c) { return Settings.canDrawOverlays(c); }

    Hud(Context c, String label, long start, Listener l) {
        this.c = c;
        this.label = label;
        this.start = start;
        this.listener = l;
        this.wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        this.prefs = c.getSharedPreferences("gb", Context.MODE_PRIVATE);
        this.scale = new float[]{0.85f, 1f, 1.18f}[Math.max(0, Math.min(2, prefs.getInt("hudSize", 1)))];
        int a = new int[]{0xE6, 0xB3, 0x80}[Math.max(0, Math.min(2, prefs.getInt("hudAlpha", 0)))];
        this.bgColor = (a << 24) | 0x080C14;
        this.autoMin = prefs.getBoolean("hudAutoMin", true);
        this.lineMode = prefs.getBoolean("hudLine", true);
        this.minimized = !autoMin && prefs.getBoolean("hudMin", false);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v * scale, c.getResources().getDisplayMetrics());
    }

    private TextView tv(float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * scale);
        t.setTextColor(color);
        t.setSingleLine(true);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView iconBtn(String text, View.OnClickListener l) {
        TextView b = tv(18, SUB, true);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(9), dp(2), dp(9), dp(2));
        b.setOnClickListener(l);
        return b;
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

    private void close() { if (listener != null) listener.onClose(); }
    private void finish() { if (listener != null) listener.onFinish(); }

    void show() {
        if (!canShow(c) || panel != null) return;
        View.OnTouchListener drag = new Drag();

        // ---- 세로 패널 ----
        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(8), dp(6), dp(12));
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = tv(14, TEXT, true);
        title.setText("⚡ " + label);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(iconBtn("–", v -> setMinimized(true)));
        head.addView(iconBtn("✕", v -> close()));
        panel.addView(head);
        LinearLayout bodyBox = new LinearLayout(c);
        bodyBox.setOrientation(LinearLayout.VERTICAL);
        bodyBox.setPadding(0, 0, dp(8), 0);
        tvWarn = tv(13, RED, true);
        tvWarn.setSingleLine(false);
        tvWarn.setVisibility(View.GONE);
        bodyBox.addView(tvWarn);
        tvRam = tv(15, TEXT, true); tvPing = tv(15, TEXT, true); tvHz = tv(15, TEXT, true);
        tvBat = tv(15, TEXT, true); tvTime = tv(15, TEXT, true);
        bodyBox.addView(row("여유 메모리", tvRam));
        bodyBox.addView(row("핑", tvPing));
        bodyBox.addView(row("화면", tvHz));
        bodyBox.addView(row("배터리", tvBat));
        bodyBox.addView(row("플레이", tvTime));
        TextView fin = tv(15, 0xFF04151B, true);
        fin.setText("끝내고 리포트 보기");
        fin.setGravity(Gravity.CENTER);
        fin.setPadding(0, dp(9), 0, dp(9));
        fin.setBackground(bg(CYAN, CYAN, dp(10)));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.topMargin = dp(8);
        fin.setOnClickListener(v -> finish());
        bodyBox.addView(fin, flp);
        panel.addView(bodyBox);
        panel.setOnTouchListener(drag);
        head.setOnTouchListener(drag);

        // ---- 가로 화면 한 줄 바 ----
        bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(4), dp(4), dp(4));
        TextView bolt = tv(13, YELLOW, true);
        bolt.setText("⚡");
        bar.addView(bolt);
        lRam = tv(13, TEXT, true); lPing = tv(13, TEXT, true); lHz = tv(13, TEXT, true);
        lBat = tv(13, TEXT, true); lTime = tv(13, TEXT, true);
        for (TextView t : new TextView[]{lRam, lPing, lHz, lBat, lTime}) {
            t.setPadding(dp(8), 0, dp(8), 0);
            bar.addView(t);
        }
        TextView endBtn = tv(12, 0xFF04151B, true);
        endBtn.setText("끝내기");
        endBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        endBtn.setBackground(bg(CYAN, CYAN, dp(8)));
        endBtn.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-2, -2);
        elp.leftMargin = dp(4);
        bar.addView(endBtn, elp);
        bar.addView(iconBtn("–", v -> setMinimized(true)));
        bar.addView(iconBtn("✕", v -> close()));
        bar.setOnTouchListener(drag);

        // ---- 접은 동그라미 ----
        bubble = new LinearLayout(c);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setGravity(Gravity.CENTER);
        bTemp = tv(15, TEXT, true);
        bTemp.setGravity(Gravity.CENTER);
        bPing = tv(11, SUB, true);
        bPing.setGravity(Gravity.CENTER);
        bubble.addView(bTemp);
        bubble.addView(bPing);
        bubble.setOnTouchListener(drag);

        lp = new WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        landscape = isLandscape();
        loadPos();
        if (!minimized && autoMin) collapseAt = System.currentTimeMillis() + AUTO_MIN_MS;
        attach();
        update();
    }

    private boolean isLandscape() {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        return dm.widthPixels > dm.heightPixels;
    }

    /** 세로·가로 위치를 따로 기억 */
    private String posKey() { return landscape ? "hudL" : "hud"; }

    private void loadPos() {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        lp.x = Math.max(0, Math.min(prefs.getInt(posKey() + "X", dp(12)), dm.widthPixels - dp(80)));
        lp.y = Math.max(0, Math.min(prefs.getInt(posKey() + "Y", landscape ? dp(8) : dp(80)), dm.heightPixels - dp(80)));
    }

    private View expandedView() { return (landscape && lineMode) ? bar : panel; }

    private void attach() {
        try {
            current = minimized ? bubble : expandedView();
            if (minimized) { lp.width = dp(64); lp.height = dp(64); }
            else if (current == panel) { lp.width = dp(236); lp.height = -2; }
            else { lp.width = -2; lp.height = -2; }
            wm.addView(current, lp);
            current.post(this::clampToScreen);
        } catch (Exception ignored) {}
    }

    private void clampToScreen() {
        if (current == null || !current.isAttachedToWindow()) return;
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int nx = Math.max(0, Math.min(lp.x, dm.widthPixels - current.getWidth()));
        int ny = Math.max(0, Math.min(lp.y, dm.heightPixels - current.getHeight()));
        if (nx != lp.x || ny != lp.y) {
            lp.x = nx; lp.y = ny;
            try { wm.updateViewLayout(current, lp); } catch (Exception ignored) {}
        }
    }

    private void detachAll() {
        for (View v : new View[]{panel, bar, bubble}) {
            try { if (v != null && v.isAttachedToWindow()) wm.removeView(v); } catch (Exception ignored) {}
        }
        current = null;
    }

    private void setMinimized(boolean m) {
        minimized = m;
        if (!autoMin) prefs.edit().putBoolean("hudMin", m).apply();
        collapseAt = (!m && autoMin) ? System.currentTimeMillis() + AUTO_MIN_MS : Long.MAX_VALUE;
        detachAll();
        attach();
        update();
    }

    void hide() {
        detachAll();
        panel = null; bar = null; bubble = null;
    }

    /** 잠깐 띄우는 안내 (휴식·과열·배터리 경고) — 접혀 있으면 펼침 */
    void flash(String msg) {
        flashMsg = msg;
        flashUntil = System.currentTimeMillis() + 15_000;
        if (minimized) setMinimized(false);
        if (autoMin) collapseAt = flashUntil + 5_000;
        update();
    }

    /** 2초마다 호출 */
    void update() {
        if (panel == null) return;
        tick++;
        long now = System.currentTimeMillis();

        // 화면 방향이 바뀌면 모양·위치 다시
        boolean land = isLandscape();
        if (land != landscape) {
            landscape = land;
            detachAll();
            loadPos();
            attach();
        }
        // 자동 접기
        if (!minimized && now >= collapseAt) { setMinimized(true); return; }

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
        long sec = (now - start) / 1000;

        int tColor = temp >= 42 ? RED : temp >= 38 ? ORANGE : GREEN;
        int pColor = ping == -1 ? RED : ping < 0 ? SUB : ping <= 60 ? GREEN : ping <= 120 ? ORANGE : RED;
        int rColor = freePct < 15 ? RED : freePct < 25 ? ORANGE : TEXT;
        String pingTxt = ping == -2 ? "측정 중" : ping == -1 ? "끊김" : ping + "ms";
        String tempTxt = temp > 0 ? String.format(Locale.US, "%.1f℃", temp) : "";
        String timeTxt = sec >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60)
                : String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60);
        boolean hot = temp >= 42;
        boolean fl = now < flashUntil;
        int border = fl ? YELLOW : hot ? RED : CYAN;

        // 세로 패널
        tvRam.setText(String.format(Locale.US, "%.1fGB (%d%%)", mi.availMem / 1073741824.0, freePct));
        tvRam.setTextColor(rColor);
        tvPing.setText(pingTxt); tvPing.setTextColor(pColor);
        tvHz.setText(Math.round(hz) + "Hz");
        tvBat.setText(lvl + "%" + (tempTxt.isEmpty() ? "" : " · " + tempTxt)); tvBat.setTextColor(tColor);
        tvTime.setText(timeTxt);
        tvWarn.setVisibility(hot || fl ? View.VISIBLE : View.GONE);
        tvWarn.setText(fl ? flashMsg : "과열 — 성능이 떨어질 수 있어요");
        tvWarn.setTextColor(fl ? YELLOW : RED);
        panel.setBackground(bg(bgColor, border, dp(16)));

        // 한 줄 바
        lRam.setText("메모리 " + freePct + "%"); lRam.setTextColor(rColor);
        lPing.setText(pingTxt); lPing.setTextColor(pColor);
        lHz.setText(Math.round(hz) + "Hz");
        lBat.setText(lvl + "%" + (tempTxt.isEmpty() ? "" : " " + tempTxt)); lBat.setTextColor(tColor);
        lTime.setText(fl ? flashMsg : timeTxt); lTime.setTextColor(fl ? YELLOW : TEXT);
        bar.setBackground(bg(bgColor, border, dp(14)));

        // 동그라미
        bTemp.setText(temp > 0 ? String.format(Locale.US, "%.0f°", temp) : lvl + "%");
        bTemp.setTextColor(tColor);
        bPing.setText(ping >= 0 ? ping + "ms" : ping == -1 ? "끊김" : "…");
        bubble.setBackground(bg(bgColor, hot ? RED : CYAN, dp(32)));
    }

    /** 끌어서 옮기기 (조금만 움직이면 탭으로 처리). 만지는 동안은 자동 접기를 미룸 */
    private class Drag implements View.OnTouchListener {
        int sx, sy; float tx, ty; boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    sx = lp.x; sy = lp.y; tx = e.getRawX(); ty = e.getRawY(); moved = false;
                    if (!minimized && autoMin) collapseAt = System.currentTimeMillis() + AUTO_MIN_MS;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (e.getRawX() - tx), dy = (int) (e.getRawY() - ty);
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                    if (moved && current != null) {
                        DisplayMetrics dm = c.getResources().getDisplayMetrics();
                        lp.x = Math.max(0, Math.min(dm.widthPixels - current.getWidth(), sx + dx));
                        lp.y = Math.max(0, Math.min(dm.heightPixels - current.getHeight(), sy + dy));
                        try { wm.updateViewLayout(current, lp); } catch (Exception ignored) {}
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (moved) prefs.edit().putInt(posKey() + "X", lp.x).putInt(posKey() + "Y", lp.y).apply();
                    else { v.performClick(); if (minimized && v == bubble) setMinimized(false); }
                    return true;
            }
            return false;
        }
    }
}
