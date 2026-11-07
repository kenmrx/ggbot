package com.ggbot.app;

import java.util.Arrays;

/**
 * DNS 报文与 IPv4/UDP 组的纯字节实现。
 * 刻意不依赖任何 Android API，方便在桌面上直接用 JVM 跑单元验证。
 */
public final class DnsPacket {

    public static final String DNS_IP = "198.18.0.1";
    public static final int DNS_PORT = 53;

    private DnsPacket() {
    }

    /** 从 DNS 报文里取 QNAME，统一转小写；非法报文返回 null。 */
    public static String parseQuestion(byte[] b, int off, int len) {
        int p = off + 12;
        int end = off + len;
        if (p >= end) return null;
        StringBuilder sb = new StringBuilder();
        while (p < end) {
            int l = b[p] & 0xff;
            if (l == 0) break;
            if ((l & 0xC0) != 0) return null;
            if (p + 1 + l > end) return null;
            if (sb.length() > 0) sb.append('.');
            for (int i = 0; i < l; i++) {
                char c = (char) (b[p + 1 + i] & 0xff);
                if (c < 32 || c > 126) return null;
                sb.append(Character.toLowerCase(c));
            }
            p += 1 + l;
            // 域名最大 253 字符（RFC 1035 的 255 字节 wire 去掉末尾根节点与长度字节）。
            // 早先按 200 截断，会把 201~253 字符的合法长域名整个丢掉、返回 null。
            if (sb.length() > 253) return null;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Question 段结束位置（QNAME + QTYPE + QCLASS 之后），失败返回 -1。 */
    public static int questionEnd(byte[] b, int off, int len) {
        int p = off + 12;
        int end = off + len;
        while (p < end) {
            int l = b[p] & 0xff;
            if (l == 0) {
                p++;
                break;
            }
            if ((l & 0xC0) != 0) return -1;
            p += 1 + l;
        }
        if (p + 4 > end) return -1;
        return p + 4;
    }

    public static int qtype(byte[] b, int qEnd) {
        if (qEnd < 4) return -1;
        return ((b[qEnd - 4] & 0xff) << 8) | (b[qEnd - 3] & 0xff);
    }

    /** 空回答，带 rcode（3 = NXDOMAIN，2 = SERVFAIL，0 = NOERROR）。 */
    public static byte[] buildError(byte[] p, int off, int len, int rcode) {
        int qe = questionEnd(p, off, len);
        if (qe < 0) return null;
        byte[] r = Arrays.copyOfRange(p, off, qe);
        r[2] = (byte) 0x81;                    // QR=1, RD=1
        r[3] = (byte) (0x80 | (rcode & 0x0f)); // RA=1 + rcode
        for (int i = 6; i < 12; i++) r[i] = 0; // AN/NS/AR 全 0
        return r;
    }

    /** 带 TC 位的空回答：告诉客户端应答太大，改走 TCP 重查。 */
    public static byte[] buildTruncated(byte[] p, int off, int len) {
        int qe = questionEnd(p, off, len);
        if (qe < 0) return null;
        byte[] r = Arrays.copyOfRange(p, off, qe);
        r[2] = (byte) 0x81;                    // QR=1, RD=1
        r[3] = (byte) 0x82;                    // RA=1 + TC=1
        for (int i = 6; i < 12; i++) r[i] = 0;
        return r;
    }

    /**
     * 上游应答的基本可信校验：长度够一个 DNS 头、事务 ID 与查询一致、QR 位是应答。
     *
     * 为什么必须有：明文 UDP:53 的应答是谁都能伪造的。不校验 ID 的话，路径上的攻击者
     * 抢先注入任意应答即可污染缓存；0 字节垃圾更糟——它会被当成「合并查询的拦截标记」
     * （哨兵就是 0 长度数组），把没拦的域名误判成拦截，或者缓存成空应答让域名静默失联。
     */
    public static boolean validUpstreamResponse(byte[] query, byte[] resp) {
        if (query == null || resp == null) return false;
        if (query.length < 12 || resp.length < 12) return false;
        if (resp[0] != query[0] || resp[1] != query[1]) return false;
        return (resp[2] & 0x80) != 0;          // QR=1 才是应答
    }

    /**
     * 把转发查询里 EDNS0 OPT 记录声明的缓冲区钳到 1232。
     * 客户端原查询通常声明 4096，上游会放心回大包；而隧道 MTU 只有 1500，
     * 单个超过 MTU 的应答包会被整个丢掉，客户端只见超时。
     * 就地修改，调用方持有的是查询副本。
     */
    public static void clampEdnsSize(byte[] q, int off, int len) {
        if (q == null || q.length < off + 12 || len < 12) return;
        int end = off + len;
        int qd = ((q[off + 4] & 0xff) << 8) | (q[off + 5] & 0xff);
        int an = ((q[off + 6] & 0xff) << 8) | (q[off + 7] & 0xff);
        int ns = ((q[off + 8] & 0xff) << 8) | (q[off + 9] & 0xff);
        int ar = ((q[off + 10] & 0xff) << 8) | (q[off + 11] & 0xff);
        int p = off + 12;
        for (int i = 0; i < qd; i++) {
            p = skipName(q, p, end);
            if (p < 0 || p + 4 > end) return;
            p += 4;
        }
        for (int i = 0; i < an + ns + ar; i++) {
            p = skipName(q, p, end);
            if (p < 0 || p + 10 > end) return;
            int type = ((q[p] & 0xff) << 8) | (q[p + 1] & 0xff);
            if (type == 41) {                  // OPT：class 字段就是 UDP 缓冲区声明
                q[p + 2] = (byte) (1232 >> 8);
                q[p + 3] = (byte) 1232;
                return;
            }
            int rdlen = ((q[p + 8] & 0xff) << 8) | (q[p + 9] & 0xff);
            p += 10 + rdlen;
        }
    }

    /** A 查询回 0.0.0.0，其余类型回空 NOERROR。 */
    public static byte[] buildNullAnswer(byte[] p, int off, int len, int type) {
        int qe = questionEnd(p, off, len);
        if (qe < 0) return null;
        if (type != 1 && type != 255) return buildError(p, off, len, 0);
        byte[] r = Arrays.copyOfRange(p, off, qe + 16);
        r[2] = (byte) 0x81;
        r[3] = (byte) 0x80;
        r[6] = 0;
        r[7] = 1;                              // ANCOUNT = 1
        for (int i = 8; i < 12; i++) r[i] = 0;
        int o = qe - off;                      // r 从 off 开始，下标必须减去偏移
        r[o] = (byte) 0xC0;                    // 指向 0x0C（问题里的 QNAME）
        r[o + 1] = 0x0C;
        r[o + 2] = 0;
        r[o + 3] = 1;                          // TYPE A
        r[o + 4] = 0;
        r[o + 5] = 1;                          // CLASS IN
        r[o + 6] = 0;
        r[o + 7] = 0;
        r[o + 8] = 0x0E;                       // TTL 3600
        r[o + 9] = 0x10;
        r[o + 10] = 0;
        r[o + 11] = 4;                         // RDLENGTH 4
        for (int i = 12; i < 16; i++) r[o + i] = 0;
        return r;
    }

    /**
     * A 查询回指定 IPv4 地址。用于把命中解密规则的域名指向虚拟 IP 池。
     * TTL 取小值，避免系统解析器长期缓存导致规则切换后仍走旧地址。
     */
    public static byte[] buildAnswerIp(byte[] p, int off, int len, byte[] ip) {
        int qe = questionEnd(p, off, len);
        if (qe < 0 || ip == null || ip.length != 4) return null;
        byte[] r = Arrays.copyOfRange(p, off, qe + 16);
        r[2] = (byte) 0x81;
        r[3] = (byte) 0x80;
        r[6] = 0;
        r[7] = 1;                              // ANCOUNT = 1
        for (int i = 8; i < 12; i++) r[i] = 0;
        int o = qe - off;                      // r 从 off 开始，下标必须减去偏移
        r[o] = (byte) 0xC0;                    // 指向 0x0C
        r[o + 1] = 0x0C;
        r[o + 2] = 0;
        r[o + 3] = 1;                          // TYPE A
        r[o + 4] = 0;
        r[o + 5] = 1;                          // CLASS IN
        r[o + 6] = 0;
        r[o + 7] = 0;
        r[o + 8] = 0;
        r[o + 9] = 5;                          // TTL 5 秒
        r[o + 10] = 0;
        r[o + 11] = 4;                         // RDLENGTH 4
        System.arraycopy(ip, 0, r, o + 12, 4);
        return r;
    }

    /**
     * 从应答里取第一条 A 记录的地址。用于在返回虚拟 IP 的同时拿到真实地址，
     * 避免隧道内的代理再去用系统解析器（那会解析回虚拟 IP 形成环路）。
     */
    public static byte[] firstA(byte[] b, int off, int len) {
        if (b == null || len < 12) return null;
        int qd = ((b[off + 4] & 0xff) << 8) | (b[off + 5] & 0xff);
        int an = ((b[off + 6] & 0xff) << 8) | (b[off + 7] & 0xff);
        if (an <= 0) return null;
        int p = off + 12;
        int end = off + len;

        for (int i = 0; i < qd; i++) {
            p = skipName(b, p, end);
            if (p < 0) return null;
            p += 4;
            if (p > end) return null;
        }

        for (int i = 0; i < an; i++) {
            p = skipName(b, p, end);
            if (p < 0 || p + 10 > end) return null;
            int type = ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff);
            int rdlen = ((b[p + 8] & 0xff) << 8) | (b[p + 9] & 0xff);
            p += 10;
            if (p + rdlen > end) return null;
            if (type == 1 && rdlen == 4) {
                return new byte[]{b[p], b[p + 1], b[p + 2], b[p + 3]};
            }
            p += rdlen;
        }
        return null;
    }

    /**
     * 读出一个可能被压缩的域名。指针以 DNS 报文起点 base 为基准。
     *
     * 报文来自网络，不可信：指针成环、越界、超长都必须立刻放弃。
     * 指针最多跳 8 次——正常解析链远到不了这个深度，而恶意构造的环能一直跳下去。
     */
    public static String readName(byte[] b, int p, int base, int end) {
        StringBuilder sb = new StringBuilder();
        int cur = p;
        int hops = 0;
        while (cur >= base && cur < end) {
            int l = b[cur] & 0xff;
            if (l == 0) return sb.length() == 0 ? "" : sb.toString();
            if ((l & 0xC0) == 0xC0) {
                if (cur + 1 >= end) return null;
                if (++hops > 8) return null;
                cur = base + (((l & 0x3f) << 8) | (b[cur + 1] & 0xff));
                continue;
            }
            if ((l & 0xC0) != 0) return null;
            if (cur + 1 + l > end) return null;
            if (sb.length() > 0) sb.append('.');
            for (int i = 0; i < l; i++) {
                char c = (char) (b[cur + 1 + i] & 0xff);
                if (c < 32 || c > 126) return null;
                sb.append(Character.toLowerCase(c));
            }
            cur += 1 + l;
            if (sb.length() > 253) return null;
        }
        return null;
    }

    /**
     * 应答里所有 CNAME 指向的目标，按出现顺序。
     *
     * 这是识别「第一方 CNAME 隐身」的唯一入口：查询的域名本身干净，
     * 但上游把它 CNAME 到某个广告/统计域。只查 QNAME 的拦截器永远看不见这一层，
     * Pi-hole 与 AdGuard Home 都是靠拆这条链才把这类追踪拦下来的。
     */
    public static String[] cnameChain(byte[] b, int off, int len) {
        if (b == null || len < 12) return new String[0];
        int qd = ((b[off + 4] & 0xff) << 8) | (b[off + 5] & 0xff);
        int an = ((b[off + 6] & 0xff) << 8) | (b[off + 7] & 0xff);
        if (an <= 0) return new String[0];
        int end = off + len;
        int p = off + 12;
        for (int i = 0; i < qd; i++) {
            p = skipName(b, p, end);
            if (p < 0) return new String[0];
            p += 4;
            if (p > end) return new String[0];
        }
        String[] out = new String[4];
        int n = 0;
        for (int i = 0; i < an; i++) {
            p = skipName(b, p, end);
            if (p < 0 || p + 10 > end) break;
            int type = ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff);
            int rdlen = ((b[p + 8] & 0xff) << 8) | (b[p + 9] & 0xff);
            int rd = p + 10;
            if (rd + rdlen > end) break;
            if (type == 5) {                        // CNAME
                // 域名必须圈在 RDATA 里：end 直接给包尾的话，RDATA 不合规
                // （没有根标签）时 readName 会越过 RDLENGTH，把后续记录的字节
                // 拼进域名——伪造一条不存在的 CNAME 链就能操纵拦截判定
                String name = readName(b, rd, off, Math.min(end, rd + rdlen));
                if (name != null && !name.isEmpty()) {
                    if (n == out.length) {
                        String[] bigger = new String[n * 2];
                        System.arraycopy(out, 0, bigger, 0, n);
                        out = bigger;
                    }
                    out[n++] = name;
                }
            }
            p = rd + rdlen;
        }
        if (n == 0) return new String[0];
        String[] r = new String[n];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }

    /** 应答里最小的记录 TTL（秒）。没有记录返回 -1，全为 0 也返回 -1（不可缓存）。 */
    public static int minTtl(byte[] b, int off, int len) {
        if (b == null || len < 12) return -1;
        int qd = ((b[off + 4] & 0xff) << 8) | (b[off + 5] & 0xff);
        int an = ((b[off + 6] & 0xff) << 8) | (b[off + 7] & 0xff);
        if (an <= 0) return -1;
        int end = off + len;
        int p = off + 12;
        for (int i = 0; i < qd; i++) {
            p = skipName(b, p, end);
            if (p < 0) return -1;
            p += 4;
            if (p > end) return -1;
        }
        int min = -1;
        for (int i = 0; i < an; i++) {
            p = skipName(b, p, end);
            if (p < 0 || p + 10 > end) break;
            long ttl = ((long) (b[p + 4] & 0xff) << 24) | ((b[p + 5] & 0xff) << 16)
                    | ((b[p + 6] & 0xff) << 8) | (b[p + 7] & 0xff);
            int rdlen = ((b[p + 8] & 0xff) << 8) | (b[p + 9] & 0xff);
            // TTL 是无符号 32 位：直接 (int) 强转的话 ≥2^31 的「永久」记录
            // 会变成负数，被 DnsCache 当成负缓存，正常应答只顶 30 秒
            if (ttl > 0 && (min < 0 || ttl < min)) {
                min = ttl > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ttl;
            }
            p += 10 + rdlen;
            if (p > end) break;
        }
        return min;
    }

    /**
     * 把应答里所有记录的 TTL 改写为 ttl 秒。
     * 过期缓存兜底时必须做这一步：否则客户端会把一份旧地址按原 TTL 再吃进去一小时。
     */
    public static int stampTtl(byte[] b, int off, int len, int ttl) {
        if (b == null || len < 12) return 0;
        int qd = ((b[off + 4] & 0xff) << 8) | (b[off + 5] & 0xff);
        int an = ((b[off + 6] & 0xff) << 8) | (b[off + 7] & 0xff);
        int end = off + len;
        int p = off + 12;
        for (int i = 0; i < qd; i++) {
            p = skipName(b, p, end);
            if (p < 0) return 0;
            p += 4;
            if (p > end) return 0;
        }
        int n = 0;
        for (int i = 0; i < an; i++) {
            p = skipName(b, p, end);
            if (p < 0 || p + 10 > end) break;
            b[p + 4] = (byte) (ttl >> 24);
            b[p + 5] = (byte) (ttl >> 16);
            b[p + 6] = (byte) (ttl >> 8);
            b[p + 7] = (byte) ttl;
            int rdlen = ((b[p + 8] & 0xff) << 8) | (b[p + 9] & 0xff);
            p += 10 + rdlen;
            n++;
            if (p > end) break;
        }
        return n;
    }

    /** 应答码：0 正常、2 SERVFAIL、3 NXDOMAIN。 */
    public static int rcode(byte[] b, int off) {
        return b == null || b.length <= off + 3 ? -1 : (b[off + 3] & 0x0f);
    }

    private static int skipName(byte[] b, int p, int end) {
        while (p < end) {
            int l = b[p] & 0xff;
            if (l == 0) return p + 1;
            if ((l & 0xC0) == 0xC0) {
                if (p + 2 > end) return -1;
                return p + 2;
            }
            if ((l & 0xC0) != 0) return -1;
            p += 1 + l;
        }
        return -1;
    }

    /** 组装回客户端的 IPv4/UDP 包：源 = 虚拟 DNS 地址，目的 = 客户端。 */
    public static byte[] buildIpUdp(byte[] payload, byte[] clientIp, int clientPort) {
        byte[] pkt = new byte[28 + payload.length];
        pkt[0] = 0x45;
        pkt[1] = 0;
        pkt[2] = (byte) (pkt.length >> 8);
        pkt[3] = (byte) pkt.length;
        pkt[8] = 64;                            // TTL
        pkt[9] = 17;                            // UDP

        System.arraycopy(toIp(DNS_IP), 0, pkt, 12, 4);
        System.arraycopy(clientIp, 0, pkt, 16, 4);

        int csum = ipChecksum(pkt, 0, 20);
        pkt[10] = (byte) (csum >> 8);
        pkt[11] = (byte) csum;

        int u = 20;
        pkt[u + 1] = (byte) DNS_PORT;
        pkt[u + 2] = (byte) (clientPort >> 8);
        pkt[u + 3] = (byte) clientPort;
        int ulen = 8 + payload.length;
        pkt[u + 4] = (byte) (ulen >> 8);
        pkt[u + 5] = (byte) ulen;
        // 0/1 端口为 0，UDP 校验和留 0（IPv4 下可选）

        System.arraycopy(payload, 0, pkt, 28, payload.length);
        return pkt;
    }

    public static int ipChecksum(byte[] b, int off, int len) {
        long sum = 0;
        for (int i = 0; i < len; i += 2) {
            int hi = b[off + i] & 0xff;
            int lo = (i + 1 < len) ? (b[off + i + 1] & 0xff) : 0;
            sum += (hi << 8) | lo;
        }
        while ((sum >> 16) != 0) sum = (sum & 0xffff) + (sum >> 16);
        return (int) (~sum & 0xffff);
    }

    public static byte[] toIp(String ip) {
        String[] parts = ip.split("\\.");
        byte[] r = new byte[4];
        for (int i = 0; i < 4; i++) r[i] = (byte) Integer.parseInt(parts[i]);
        return r;
    }

    /** 构造一个 A 记录的查询报文，供测试与自检使用。 */
    public static byte[] buildQuery(String domain) {
        byte[] name = domain.getBytes(java.nio.charset.Charset.forName("UTF-8"));
        int qlen = name.length + 2;
        byte[] p = new byte[12 + qlen + 4];
        p[0] = 0x12;
        p[1] = 0x34;
        p[2] = 0x01;                            // RD=1
        p[3] = 0x00;
        p[5] = 1;                               // QDCOUNT 1
        int p2 = 12;
        int start = 0;
        for (int i = 0; i <= name.length; i++) {
            if (i == name.length || name[i] == '.') {
                int l = i - start;
                p[p2++] = (byte) l;
                for (int k = 0; k < l; k++) p[p2++] = name[start + k];
                start = i + 1;
            }
        }
        p[p2++] = 0;                            // QNAME 结束
        p[p2++] = 0;
        p[p2++] = 1;                            // QTYPE A
        p[p2++] = 0;
        p[p2++] = 1;                            // QCLASS IN
        return p;
    }
}
