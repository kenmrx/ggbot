package com.ggbot.app;

/**
 * IPv4 + TCP 报文的纯字节编解码。
 *
 * 只实现本项目需要的最小集合：解析头部各字段、按给定序列号与标志位构造数据段。
 * 不依赖 Android，桌面 JVM 可直接单测。
 */
public final class TcpPacket {

    public static final int FIN = 0x01;
    public static final int SYN = 0x02;
    public static final int RST = 0x04;
    public static final int PSH = 0x08;
    public static final int ACK = 0x10;

    /** 单个 TCP 段可承载的最大载荷，留出头部余量，避免超过 1500 的 MTU。 */
    public static final int MSS = 1400;

    private TcpPacket() {
    }

    /** 解析结果容器，复用以减少分配。 */
    public static final class Seg {
        public byte[] srcIp = new byte[4];
        public byte[] dstIp = new byte[4];
        public int srcPort;
        public int dstPort;
        public long seq;
        public long ack;
        public int flags;
        public int window;
        public int payloadOff;
        public int payloadLen;

        public boolean has(int f) {
            return (flags & f) != 0;
        }
    }

    /**
     * 解析一个 IPv4/TCP 包。成功返回 true，非 IPv4 或非 TCP 返回 false。
     */
    public static boolean parse(byte[] p, int len, Seg out) {
        if (p == null || len < 40) return false;
        int vihl = p[0] & 0xff;
        if ((vihl >> 4) != 4) return false;
        int ihl = (vihl & 0x0f) * 4;
        if (ihl < 20 || len < ihl + 20) return false;
        if ((p[9] & 0xff) != 6) return false; // 只处理 TCP

        int total = ((p[2] & 0xff) << 8) | (p[3] & 0xff);
        int avail = Math.min(total, len);
        if (avail < ihl + 20) return false;

        System.arraycopy(p, 12, out.srcIp, 0, 4);
        System.arraycopy(p, 16, out.dstIp, 0, 4);

        int t = ihl;
        out.srcPort = ((p[t] & 0xff) << 8) | (p[t + 1] & 0xff);
        out.dstPort = ((p[t + 2] & 0xff) << 8) | (p[t + 3] & 0xff);
        out.seq = u32(p, t + 4);
        out.ack = u32(p, t + 8);

        int dataOff = ((p[t + 12] & 0xf0) >> 4) * 4;
        if (dataOff < 20) return false;
        out.flags = p[t + 13] & 0xff;
        out.window = ((p[t + 14] & 0xff) << 8) | (p[t + 15] & 0xff);

        int bodyOff = t + dataOff;
        if (bodyOff > avail) return false;
        out.payloadOff = bodyOff;
        out.payloadLen = avail - bodyOff;
        return true;
    }

    public static byte[] build(byte[] payload, int plen, byte[] srcIp, byte[] dstIp,
                               int srcPort, int dstPort, long seq, long ack,
                               int flags, int window) {
        return buildFrom(payload, 0, plen, srcIp, dstIp, srcPort, dstPort, seq, ack, flags, window);
    }

    /** 同上，但载荷从 off 开始取，避免为每个分片单独复制一次数组。 */
    public static byte[] buildFrom(byte[] payload, int off, int plen, byte[] srcIp, byte[] dstIp,
                                   int srcPort, int dstPort, long seq, long ack,
                                   int flags, int window) {
        int n = plen < 0 ? 0 : plen;
        byte[] pkt = new byte[40 + n];

        pkt[0] = 0x45;
        pkt[1] = 0;
        int total = 40 + n;
        pkt[2] = (byte) (total >> 8);
        pkt[3] = (byte) total;
        pkt[4] = 0;
        pkt[5] = 0;
        pkt[6] = 0x40;              // DF
        pkt[7] = 0;
        pkt[8] = 64;                // TTL
        pkt[9] = 6;                 // TCP
        System.arraycopy(srcIp, 0, pkt, 12, 4);
        System.arraycopy(dstIp, 0, pkt, 16, 4);

        int csum = DnsPacket.ipChecksum(pkt, 0, 20);
        pkt[10] = (byte) (csum >> 8);
        pkt[11] = (byte) csum;

        int t = 20;
        pkt[t] = (byte) (srcPort >> 8);
        pkt[t + 1] = (byte) srcPort;
        pkt[t + 2] = (byte) (dstPort >> 8);
        pkt[t + 3] = (byte) dstPort;
        put32(pkt, t + 4, seq);
        put32(pkt, t + 8, ack);
        pkt[t + 12] = 0x50;         // data offset = 5（20 字节，无选项）
        pkt[t + 13] = (byte) flags;
        pkt[t + 14] = (byte) (window >> 8);
        pkt[t + 15] = (byte) window;
        pkt[t + 16] = 0;            // 校验和占位
        pkt[t + 17] = 0;
        pkt[t + 18] = 0;            // 紧急指针
        pkt[t + 19] = 0;

        if (n > 0) System.arraycopy(payload, off, pkt, 40, n);

        int tcpCsum = tcpChecksum(pkt, srcIp, dstIp, 20, 20 + n);
        pkt[t + 16] = (byte) (tcpCsum >> 8);
        pkt[t + 17] = (byte) tcpCsum;
        return pkt;
    }

    /** 只带标志位的空段（SYN / ACK / FIN / RST）。 */
    public static byte[] control(byte[] srcIp, byte[] dstIp, int srcPort, int dstPort,
                                 long seq, long ack, int flags, int window) {
        return build(null, 0, srcIp, dstIp, srcPort, dstPort, seq, ack, flags, window);
    }

    /** TCP 校验和，含 IPv4 伪头。 */
    public static int tcpChecksum(byte[] pkt, byte[] srcIp, byte[] dstIp, int off, int len) {
        long sum = 0;
        for (int i = 0; i < 4; i += 2) {
            sum += ((srcIp[i] & 0xff) << 8) | (srcIp[i + 1] & 0xff);
        }
        for (int i = 0; i < 4; i += 2) {
            sum += ((dstIp[i] & 0xff) << 8) | (dstIp[i + 1] & 0xff);
        }
        sum += 6;
        sum += len;
        for (int i = 0; i < len; i += 2) {
            int hi = pkt[off + i] & 0xff;
            int lo = (i + 1 < len) ? (pkt[off + i + 1] & 0xff) : 0;
            sum += (hi << 8) | lo;
        }
        while ((sum >> 16) != 0) sum = (sum & 0xffff) + (sum >> 16);
        return (int) (~sum & 0xffff);
    }

    public static long u32(byte[] b, int off) {
        return ((long) (b[off] & 0xff) << 24)
                | ((long) (b[off + 1] & 0xff) << 16)
                | ((long) (b[off + 2] & 0xff) << 8)
                | (long) (b[off + 3] & 0xff);
    }

    public static void put32(byte[] b, int off, long v) {
        b[off] = (byte) (v >> 24);
        b[off + 1] = (byte) (v >> 16);
        b[off + 2] = (byte) (v >> 8);
        b[off + 3] = (byte) v;
    }

    public static String flagsText(int f) {
        StringBuilder sb = new StringBuilder();
        if ((f & SYN) != 0) sb.append("S");
        if ((f & ACK) != 0) sb.append("A");
        if ((f & FIN) != 0) sb.append("F");
        if ((f & RST) != 0) sb.append("R");
        if ((f & PSH) != 0) sb.append("P");
        return sb.length() == 0 ? "-" : sb.toString();
    }
}
