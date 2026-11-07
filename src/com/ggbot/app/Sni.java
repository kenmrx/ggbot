package com.ggbot.app;

/**
 * 从 TLS ClientHello 里取 SNI（纯逻辑，可单测）。
 *
 * 为什么必须自己解：隧道层只能看到 IP 和 DNS，看不到“客户端到底要访问哪个域名”。
 * 同一个 IP 上挂多个站点（CDN、共享证书）时，拿 DNS 或反解结果签叶子，
 * 客户端会判“证书名称与主机不符”而断连——那不是 pinning，是我们签错了名字。
 *
 * 只解到 server_name 扩展，不做完整握手，也不信任包内长度以外的任何东西。
 */
public final class Sni {

    private Sni() {
    }

    /** 已经收到的字节是否够判断（完整一条 ClientHello 记录或已含 SNI）。 */
    public static boolean complete(byte[] b, int off, int len) {
        if (len < 5) return false;
        int recLen = ((b[off + 3] & 0xFF) << 8) | (b[off + 4] & 0xFF);
        return len >= 5 + recLen;
    }

    /** 取 SNI；取不到（非 TLS、无该扩展、包里不够长）返回 null。 */
    public static String host(byte[] b, int off, int len) {
        try {
            if (len < 9) return null;
            if ((b[off] & 0xFF) != 0x16) return null;          // handshake
            int p = off + 5;
            if ((b[p] & 0xFF) != 0x01) return null;            // ClientHello
            int hsLen = ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
            int hsEnd = Math.min(off + len, p + 4 + hsLen);
            p += 4;
            if (p + 34 > hsEnd) return null;
            p += 34;                                            // version(2) + random(32)
            int sidLen = b[p] & 0xFF;
            p += 1 + sidLen;
            if (p + 2 > hsEnd) return null;
            int csLen = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
            p += 2 + csLen;
            if (p + 1 > hsEnd) return null;
            int compLen = b[p] & 0xFF;
            p += 1 + compLen;
            if (p + 2 > hsEnd) return null;
            int extTotal = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
            p += 2;
            int extEnd = Math.min(hsEnd, p + extTotal);
            while (p + 4 <= extEnd) {
                int type = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
                int elen = ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
                p += 4;
                if (p + elen > extEnd) return null;
                if (type == 0x0000) return serverName(b, p, elen);
                p += elen;
            }
            return null;
        } catch (RuntimeException e) {
            return null;   // 半包/畸形包一律当“没取到”，绝不把解析异常传给握手
        }
    }

    private static String serverName(byte[] b, int p, int len) {
        if (len < 5) return null;
        int listLen = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
        int q = p + 2;
        int end = Math.min(p + 2 + listLen, p + len);
        while (q + 3 <= end) {
            int type = b[q] & 0xFF;
            int nlen = ((b[q + 1] & 0xFF) << 8) | (b[q + 2] & 0xFF);
            q += 3;
            if (q + nlen > end) return null;
            if (type == 0) {
                String s = new String(b, q, nlen, java.nio.charset.Charset.forName("US-ASCII"));
                s = s.trim().toLowerCase(java.util.Locale.US);
                if (s.isEmpty()) return null;
                // SNI 是客户端可控的：控制字符放过去会污染下游（证书 CN、
                // RealResolver 的 QNAME 里进 0x00 就是畸形查询）。可打印 ASCII 之外一律拒
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (c < 0x21 || c > 0x7E) return null;
                }
                return s;
            }
            q += nlen;
        }
        return null;
    }
}
