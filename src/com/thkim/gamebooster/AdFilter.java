package com.thkim.gamebooster;

import java.util.Locale;

/**
 * 광고 도메인 판별 + DNS·IP 패킷 처리 (안드로이드에 의존하지 않아 단위 시험 가능).
 * 목록은 모바일 게임에서 많이 쓰는 광고 SDK의 광고 전용 도메인만 담았다.
 * 게임 로그인·결제·게임 서버에 쓰이는 넓은 도메인(google.com, facebook.com, unity3d.com 등)은 넣지 않았다.
 */
public final class AdFilter {

    private AdFilter() {}

    /** 이 도메인 또는 하위 도메인이면 차단 */
    static final String[] BLOCK = {
            // Google AdMob / Ad Manager
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com",
            "adservice.google.com", "pagead2.googlesyndication.com",
            // Unity Ads (광고 전용 하위 도메인만)
            "unityads.unity3d.com", "ads.prd.ie.internal.unity3d.com",
            // AppLovin / MAX
            "applovin.com", "applvn.com",
            // ironSource
            "supersonicads.com", "ironsrc.mobi",
            // Vungle / Liftoff, Chartboost, AdColony, InMobi
            "vungle.com", "chartboost.com", "adcolony.com", "inmobi.com",
            // Mintegral
            "mintegral.com", "rayjump.com", "mtgglobals.com",
            // Pangle (광고 전용)
            "pangle.io", "pangolin-sdk-toutiao.com", "pangolin-sdk-toutiao-b.com",
            // Meta Audience Network (광고 전용 하위 도메인만)
            "an.facebook.com",
            // Tapjoy, Fyber / Digital Turbine, Moloco, Smaato, MoPub, Start.io, Amazon
            "tapjoy.com", "tapjoyads.com", "fyber.com", "inner-active.mobi", "adsmoloco.com",
            "smaato.net", "mopub.com", "startappservice.com", "amazon-adsystem.com",
            // 국내: 카카오 애드핏, 애드팝콘, 카울리
            "ad.daum.net", "adfit.kakao.com", "adpopcorn.com", "cauly.co.kr",
    };

    static boolean isBlocked(String name) {
        if (name == null || name.isEmpty()) return false;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".")) n = n.substring(0, n.length() - 1);
        for (String d : BLOCK) {
            if (n.equals(d) || n.endsWith("." + d)) return true;
        }
        return false;
    }

    // ---------- DNS ----------

    /** DNS 질문의 도메인 이름 (실패 시 null) */
    static String queryName(byte[] dns, int off, int len) {
        if (len < 13) return null;
        int qd = ((dns[off + 4] & 0xFF) << 8) | (dns[off + 5] & 0xFF);
        if (qd < 1) return null;
        StringBuilder sb = new StringBuilder();
        int p = off + 12, end = off + len;
        while (p < end) {
            int l = dns[p] & 0xFF;
            if (l == 0) return sb.toString();
            if ((l & 0xC0) != 0 || p + 1 + l > end) return null;   // 질문에 압축 포인터는 없어야 함
            if (sb.length() > 0) sb.append('.');
            for (int i = 0; i < l; i++) sb.append((char) (dns[p + 1 + i] & 0xFF));
            p += 1 + l;
        }
        return null;
    }

    /** 질문 부분이 끝나는 위치 (이름 + QTYPE + QCLASS), 실패 시 -1 */
    static int questionEnd(byte[] dns, int off, int len) {
        int p = off + 12, end = off + len;
        while (p < end) {
            int l = dns[p] & 0xFF;
            if (l == 0) { p += 1 + 4; return p <= end ? p : -1; }
            if ((l & 0xC0) != 0) return -1;
            p += 1 + l;
        }
        return -1;
    }

    /** 차단 응답 (NXDOMAIN): 질문을 그대로 돌려주고 '없는 주소'라고 답한다 */
    static byte[] blockedResponse(byte[] dns, int off, int len) {
        int qe = questionEnd(dns, off, len);
        if (qe < 0) return null;
        byte[] r = new byte[qe - off];
        System.arraycopy(dns, off, r, 0, r.length);
        r[2] = (byte) (0x80 | (r[2] & 0x79));      // QR=1, opcode·RD 유지
        r[3] = (byte) (0x80 | 3);                   // RA=1, RCODE=3 (NXDOMAIN)
        r[4] = 0; r[5] = 1;                         // QDCOUNT=1
        r[6] = 0; r[7] = 0; r[8] = 0; r[9] = 0; r[10] = 0; r[11] = 0;
        return r;
    }

    // ---------- IPv4 / UDP ----------

    /** IPv4 UDP 53번 포트 패킷이면 DNS 내용 시작 위치, 아니면 -1 */
    static int dnsPayloadOffset(byte[] pkt, int len) {
        if (len < 28 || ((pkt[0] >> 4) & 0xF) != 4) return -1;
        int ihl = (pkt[0] & 0xF) * 4;
        if (ihl < 20 || len < ihl + 8 || (pkt[9] & 0xFF) != 17) return -1;
        int dport = ((pkt[ihl + 2] & 0xFF) << 8) | (pkt[ihl + 3] & 0xFF);
        if (dport != 53) return -1;
        return ihl + 8;
    }

    /** 질문 패킷에 대한 답 패킷 만들기 (출발·도착 주소와 포트를 맞바꿈) */
    static byte[] buildReply(byte[] query, byte[] dns, int dnsLen) {
        int ihl = (query[0] & 0xF) * 4;
        int total = 20 + 8 + dnsLen;
        byte[] p = new byte[total];
        p[0] = 0x45;
        p[2] = (byte) (total >> 8); p[3] = (byte) total;
        p[6] = 0x40;                     // Don't Fragment
        p[8] = 64;                       // TTL
        p[9] = 17;                       // UDP
        System.arraycopy(query, 16, p, 12, 4);   // 출발 = 원래 도착
        System.arraycopy(query, 12, p, 16, 4);   // 도착 = 원래 출발
        int cs = ipChecksum(p, 0, 20);
        p[10] = (byte) (cs >> 8); p[11] = (byte) cs;
        // UDP
        p[20] = query[ihl + 2]; p[21] = query[ihl + 3];  // 출발 포트 = 원래 도착 포트(53)
        p[22] = query[ihl];     p[23] = query[ihl + 1];  // 도착 포트 = 원래 출발 포트
        int ulen = 8 + dnsLen;
        p[24] = (byte) (ulen >> 8); p[25] = (byte) ulen;
        // UDP 체크섬 0 = 사용 안 함 (IPv4에서 허용)
        System.arraycopy(dns, 0, p, 28, dnsLen);
        return p;
    }

    static int ipChecksum(byte[] b, int off, int len) {
        long sum = 0;
        for (int i = off; i < off + len; i += 2) {
            sum += ((b[i] & 0xFF) << 8) | (i + 1 < off + len ? (b[i + 1] & 0xFF) : 0);
        }
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum & 0xFFFF);
    }
}
