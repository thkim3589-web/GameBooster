package com.thkim.gamebooster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** SurfaceFlinger 출력 해석과 FPS 계산 (안드로이드에 의존하지 않아 단위 시험 가능) */
final class FpsCalc {
    private FpsCalc() {}

    /** 긴 게임용: 시각을 모두 저장하지 않고 누적 계산. 1초 넘는 공백(게임을 나감 등)은 제외 */
    static final class Stream {
        private long last, activeNs;
        private int frames;
        private final int[] hist = new int[1001];   // 프레임 간격(ms) 분포

        synchronized void add(long t) {
            if (t <= last) return;
            if (last > 0) {
                long gap = t - last;
                if (gap <= 1_000_000_000L) {
                    frames++;
                    activeNs += gap;
                    hist[(int) Math.min(1000, Math.round(gap / 1e6))]++;
                }
            }
            last = t;
        }

        synchronized Result result() {
            Result r = new Result();
            r.frames = frames;
            if (frames < 30 || activeNs <= 0) return r;
            r.seconds = activeNs / 1e9;
            r.avgFps = frames / r.seconds;
            int seen = 0, median = 0, p99 = 0;
            for (int ms = 0; ms <= 1000; ms++) {
                int before = seen;
                seen += hist[ms];
                if (before < frames / 2 && seen >= frames / 2) median = ms;
                if (before < frames * 0.99 && seen >= frames * 0.99) p99 = ms;
            }
            r.low1Fps = p99 > 0 ? 1000.0 / p99 : 0;
            for (int ms = median * 2 + 1; ms <= 1000; ms++) r.stutters += hist[ms];
            r.ok = true;
            return r;
        }
    }

    /** 계산 결과. frames가 부족하면 ok=false */
    static final class Result {
        boolean ok; int frames; double avgFps, low1Fps; int stutters; double seconds;
    }

    /** 최신 안드로이드의 RequestedLayerState{이름 parentId=...} 형식에서 실제 레이어 이름만 꺼낸다 */
    static String cleanName(String raw) {
        String l = raw.trim();
        String prefix = "RequestedLayerState{";
        if (l.startsWith(prefix)) {
            l = l.substring(prefix.length());
            if (l.endsWith("}")) l = l.substring(0, l.length() - 1);
            int cut = l.indexOf(" parentId=");
            if (cut < 0) cut = l.indexOf(" relativeParentId=");
            if (cut < 0) cut = l.indexOf(" z=");
            if (cut >= 0) l = l.substring(0, cut);
        }
        return l.trim();
    }

    /** 이름 끝의 #번호를 뺀 형태 (기기에 따라 이 이름으로만 조회될 수 있음) */
    static String withoutId(String name) {
        return name.replaceAll("#\\d+$", "");
    }

    /** dumpsys SurfaceFlinger --list 에서 이 게임의 측정 후보 레이어 (SurfaceView 우선, 최대 max개) */
    static List<String> candidates(String listOutput, String pkg, int max) {
        List<String> sv = new ArrayList<>(), other = new ArrayList<>();
        if (listOutput == null) return sv;
        for (String raw : listOutput.split("\n")) {
            String l = cleanName(raw);
            if (l.isEmpty() || !l.contains(pkg)) continue;
            String low = l.toLowerCase(Locale.ROOT);
            if (low.startsWith("background for") || low.startsWith("bounds for") || low.contains("activityrecord")
                    || low.startsWith("task=") || low.contains("animation")) continue;
            if (low.contains("surfaceview") && low.contains("(blast)")) { if (!sv.contains(l)) sv.add(0, l); }
            else if (low.contains("surfaceview")) { if (!sv.contains(l)) sv.add(l); }
            else if (!other.contains(l)) other.add(l);
        }
        sv.addAll(other);
        return sv.size() > max ? new ArrayList<>(sv.subList(0, max)) : sv;
    }

    /** --latency 출력에서 실제 표시 시각(둘째 칸)을 모은다. 첫 줄은 화면 주기(ns)이고 이를 돌려준다 (없으면 0) */
    static long parseLatency(String out, Set<Long> present) {
        if (out == null) return 0;
        String[] lines = out.split("\n");
        long period = 0;
        try { if (lines.length > 0) period = Long.parseLong(lines[0].trim()); } catch (NumberFormatException ignored) {}
        for (int i = 1; i < lines.length; i++) {
            String[] p = lines[i].trim().split("\\s+");
            if (p.length < 3) continue;
            try {
                long t = Long.parseLong(p[1]);
                if (t > 0 && t < Long.MAX_VALUE) present.add(t);   // 0·최댓값은 아직 표시되지 않은 프레임
            } catch (NumberFormatException ignored) {}
        }
        return period;
    }

    /** 정렬된 표시 시각으로 평균 FPS, 하위 1% FPS, 끊김 횟수(중앙값의 2배 넘는 간격)를 계산 */
    static Result compute(Set<Long> sortedPresent) {
        Result r = new Result();
        r.frames = sortedPresent.size();
        if (r.frames < 30) return r;
        List<Long> ts = new ArrayList<>(sortedPresent), gaps = new ArrayList<>();
        for (int i = 1; i < ts.size(); i++) gaps.add(ts.get(i) - ts.get(i - 1));
        long span = ts.get(ts.size() - 1) - ts.get(0);
        if (span <= 0) return r;
        r.seconds = span / 1e9;
        r.avgFps = (ts.size() - 1) / r.seconds;
        Collections.sort(gaps);
        long median = gaps.get(gaps.size() / 2);
        long p99 = gaps.get(Math.min(gaps.size() - 1, (int) (gaps.size() * 0.99)));
        r.low1Fps = p99 > 0 ? 1e9 / p99 : 0;
        for (long g : gaps) if (g > median * 2) r.stutters++;
        r.ok = true;
        return r;
    }
}
