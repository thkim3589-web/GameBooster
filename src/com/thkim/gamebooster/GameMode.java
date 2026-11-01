package com.thkim.gamebooster;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;

import org.json.JSONObject;

/**
 * 게임별 모드: 방해금지, 화면 켜짐 유지, 미디어 볼륨, 휴식 알림.
 * 게임 시작 때 apply(), 끝날 때 restore()로 원래대로 돌려놓는다.
 */
public class GameMode {

    static final String DEFAULT = "_default";

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("gb", Context.MODE_PRIVATE); }

    static JSONObject all(Context c) {
        try { return new JSONObject(prefs(c).getString("gameModes", "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    /** 기본 모드 (처음엔 예전 '부스트할 때 방해금지 자동' 설정을 이어받음) */
    static JSONObject defaults(Context c) {
        JSONObject d = all(c).optJSONObject(DEFAULT);
        if (d != null) return d;
        try {
            d = new JSONObject();
            d.put("dnd", prefs(c).getBoolean("autoDnd", false));
            d.put("keepScreen", false);
            d.put("mediaVol", -1);
            d.put("breakMin", 0);
        } catch (Exception ignored) {}
        return d;
    }

    /** 실제로 적용될 설정: 게임 전용 설정이 켜져 있으면 그것, 아니면 기본 모드 */
    static JSONObject effective(Context c, String pkg) {
        JSONObject g = all(c).optJSONObject(pkg);
        if (g != null && g.optBoolean("own", false)) return g;
        return defaults(c);
    }

    static void save(Context c, String pkg, JSONObject mode) {
        try {
            JSONObject a = all(c);
            a.put(pkg, mode);
            prefs(c).edit().putString("gameModes", a.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** 게임 시작 직전. 바꾸기 전 상태를 기억해 둔다. 적용된 모드를 돌려준다. */
    static JSONObject apply(Context c, String pkg) {
        JSONObject m = effective(c, pkg);
        JSONObject restore = new JSONObject();
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (m.optBoolean("dnd") && nm.isNotificationPolicyAccessGranted()) {
                int cur = nm.getCurrentInterruptionFilter();
                if (cur == NotificationManager.INTERRUPTION_FILTER_ALL) {
                    restore.put("dnd", cur);
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                }
            }
            int vol = m.optInt("mediaVol", -1);
            if (vol >= 0) {
                AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                restore.put("vol", am.getStreamVolume(AudioManager.STREAM_MUSIC));
                am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * vol / 100f), 0);
            }
            prefs(c).edit().putString("modeRestore", restore.toString()).apply();
        } catch (Exception ignored) {}
        return m;
    }

    /** 게임이 끝나면 원래대로 */
    static void restore(Context c) {
        String s = prefs(c).getString("modeRestore", null);
        if (s == null) return;
        prefs(c).edit().remove("modeRestore").apply();
        try {
            JSONObject r = new JSONObject(s);
            if (r.has("dnd")) {
                NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm.isNotificationPolicyAccessGranted()) nm.setInterruptionFilter(r.getInt("dnd"));
            }
            if (r.has("vol")) {
                AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, r.getInt("vol"), 0);
            }
        } catch (Exception ignored) {}
    }

    /** 휴식 알림 (방해금지 중에도 보이도록 높은 중요도 채널) */
    static void breakNotice(Context c, int minutes) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel("break", "휴식 알림", NotificationManager.IMPORTANCE_HIGH);
            ch.setBypassDnd(true);
            nm.createNotificationChannel(ch);
            Notification n = new Notification.Builder(c, "break")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("⏰ " + minutes + "분 동안 게임했어요")
                    .setContentText("눈과 손목을 위해 잠깐 쉬어가세요")
                    .setAutoCancel(true)
                    .setTimeoutAfter(60_000)
                    .build();
            nm.notify(88, n);
        } catch (Exception ignored) {}
    }
}
