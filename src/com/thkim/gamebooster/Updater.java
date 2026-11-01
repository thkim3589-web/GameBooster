package com.thkim.gamebooster;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 새 버전 확인·다운로드·설치.
 * GitHub 저장소 thkim3589-web/GameBooster 의 최신 릴리스(태그 "v<버전코드>")를 확인한다.
 */
public class Updater {

    public static final String REPO = "thkim3589-web/GameBooster";
    public static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    public static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases/latest";
    public static final String ACTION_INSTALL_STATUS = "com.thkim.gamebooster.INSTALL_STATUS";
    private static final String CH = "update";
    private static final int JOB_ID = 7001;

    public interface Progress { void on(int pct); }

    public static long myVersionCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) { return 0; }
    }

    public static String myVersionName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    private static String httpGet(String u) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(u).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(10000);
        con.setRequestProperty("Accept", "application/vnd.github+json");
        con.setRequestProperty("User-Agent", "GameBooster-App");
        int code = con.getResponseCode();
        if (code != 200) { con.disconnect(); throw new Exception("HTTP " + code); }
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } finally { con.disconnect(); }
    }

    /** 새 버전이 있으면 정보, 없으면 {"none":true}, 실패하면 {"error":...} */
    public static JSONObject check(Context c) {
        JSONObject r = new JSONObject();
        try {
            r.put("current", myVersionName(c));
            r.put("currentCode", myVersionCode(c));
            JSONObject rel = new JSONObject(httpGet(API));
            String tag = rel.optString("tag_name", "");
            long code = parseCode(tag);
            String apk = null;
            JSONArray assets = rel.optJSONArray("assets");
            if (assets != null) for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name").toLowerCase().endsWith(".apk")) {
                    apk = a.optString("browser_download_url");
                    r.put("size", a.optLong("size"));
                    break;
                }
            }
            if (code <= myVersionCode(c) || apk == null) { r.put("none", true); return r; }
            r.put("tag", tag);
            r.put("code", code);
            r.put("name", rel.optString("name", tag));
            r.put("notes", rel.optString("body", ""));
            r.put("url", apk);
            return r;
        } catch (Exception e) {
            try { r.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) {}
            return r;
        }
    }

    /** "v105" → 105, "1.2(105)" 같은 형식도 마지막 숫자 묶음 사용 */
    static long parseCode(String tag) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(tag);
        long last = 0;
        while (m.find()) last = Long.parseLong(m.group(1));
        return last;
    }

    /** APK를 받아서 시스템 설치 화면까지 띄운다. 결과는 ACTION_INSTALL_STATUS 브로드캐스트로 온다. */
    public static void downloadAndInstall(Context c, String url, Progress p) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setInstanceFollowRedirects(true);
        con.setConnectTimeout(10000);
        con.setReadTimeout(20000);
        con.setRequestProperty("User-Agent", "GameBooster-App");
        // GitHub 다운로드는 다른 주소로 넘겨주므로 직접 따라감
        int hops = 0;
        while ((con.getResponseCode() / 100) == 3 && hops++ < 5) {
            String loc = con.getHeaderField("Location");
            con.disconnect();
            con = (HttpURLConnection) new URL(loc).openConnection();
            con.setConnectTimeout(10000);
            con.setReadTimeout(20000);
            con.setRequestProperty("User-Agent", "GameBooster-App");
        }
        if (con.getResponseCode() != 200) throw new Exception("다운로드 실패 (HTTP " + con.getResponseCode() + ")");
        long total = con.getContentLengthLong();

        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        if (total > 0) params.setSize(total);
        int sid = pi.createSession(params);
        PackageInstaller.Session s = pi.openSession(sid);
        try (InputStream in = con.getInputStream(); OutputStream out = s.openWrite("update.apk", 0, total > 0 ? total : -1)) {
            byte[] buf = new byte[65536]; int n; long done = 0; int lastPct = -1;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct != lastPct) { lastPct = pct; p.on(pct); }
                }
            }
            s.fsync(out);
        } catch (Exception e) {
            s.abandon();
            throw e;
        } finally { con.disconnect(); }

        Intent i = new Intent(ACTION_INSTALL_STATUS).setPackage(c.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent pend = PendingIntent.getBroadcast(c, sid, i, flags);
        s.commit(pend.getIntentSender());
        s.close();
    }

    // ---------- 백그라운드 알림 ----------

    /** 하루 두 번(약 12시간마다) 새 버전 확인 예약 */
    public static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, UpdateJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(12L * 60 * 60 * 1000)
                .setPersisted(true)
                .build();
        try { js.schedule(job); } catch (Exception ignored) {}
    }

    /** 새 버전이면 알림 (같은 버전은 한 번만) */
    public static void notifyIfNew(Context c, JSONObject info) {
        if (info == null || info.optBoolean("none") || info.has("error")) return;
        SharedPreferences prefs = c.getSharedPreferences("gb", Context.MODE_PRIVATE);
        String tag = info.optString("tag");
        if (tag.equals(prefs.getString("notifiedTag", ""))) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 33 && !nm.areNotificationsEnabled()) return;
        nm.createNotificationChannel(new NotificationChannel(CH, "업데이트 알림", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("fromUpdate", true);
        PendingIntent pi = PendingIntent.getActivity(c, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String notes = info.optString("notes", "").trim();
        Notification n = new Notification.Builder(c, CH)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("게임부스터 새 버전 " + info.optString("name"))
                .setContentText(notes.isEmpty() ? "눌러서 업데이트하세요" : notes.split("\n")[0])
                .setStyle(new Notification.BigTextStyle().bigText(notes.isEmpty() ? "눌러서 업데이트하세요" : notes))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(42, n);
        prefs.edit().putString("notifiedTag", tag).apply();
    }
}
