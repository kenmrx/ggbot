package com.ggbot.app;

/**
 * ICMP 差错报文的最小实现，只造一种：目的端口不可达。
 *
 * 为什么需要它：解密域名被解析成虚拟地址后，应用如果先试 QUIC（UDP 443），
 * 这些包虽然进了隧道，却没法转发出去——隧道里只有 TCP 转发。早先的做法是直接丢弃，
 * 后果是客户端要等自己的 QUIC 超时（Chrome 是几百毫秒到数秒不等）才回落 TCP，
 * 期间页面白着、视频转圈，用户只会认为「开了这个 App 就卡」。
 *
 * 回一个 ICMP 端口不可达，内核会立刻把错误交给对应的 UDP socket，
 * 应用拿到 ECONNREFUSED 就知道这条路不通，几百毫秒内改用 TCP。
 * 这也是各家防火墙「拒绝」而不是「丢弃」QUIC 时的标准做法。
 *
 * 报文格式（RFC 792）：
 *   IPv4 头 20 字节，源 = 原报文的目的地址，目的 = 原报文的源地址
 *   ICMP 头 8 字节：type=3 code=3，校验和覆盖 ICMP 头 + 数据
 *   数据 = 原 IP 头 + 原 UDP 头前 8 字节（内核靠这段反查是哪个 socket）
 */
public final class Icmp {

    public static final int TYPE_UNREACHABLE = 3;
    public static final int CODE_PORT = 3;

    private Icmp() {
    }

    /**
     * 由一条原始 IPv4/UDP 报文生成对应的端口不可达报文。
     *
     * @param pkt 原始报文，从 IPv4 头开始
     * @param len 原始报文的有效长度
     * @return 可直接写回隧道的完整 IPv4/ICMP 报文；报文不合法时返回 null
     */
    public static byte[] portUnreachable(byte[] pkt, int len) {
        return unreachable(pkt, len, CODE_PORT);
    }

    public static byte[] unreachable(byte[] pkt, int len, int code) {
        if (pkt == null || len < 28) return null;
        int vihl = pkt[0] & 0xff;
        if ((vihl >> 4) != 4) return null;
        int ihl = (vihl & 0x0f) * 4;
        if (ihl < 20 || len < ihl + 8) return null;

        // 引用原 IP 头 + 8 字节传输层头。协议要求至少 8 字节，
        // 多一点也行，但没必要——多引反而可能让整包超过 576 字节。
        int quote = ihl + 8;
        int total = 20 + 8 + quote;
        byte[] out = new byte[total];

        // 外层 IPv4 头
        out[0] = 0x45;
        out[1] = 0;
        out[2] = (byte) (total >> 8);
        out[3] = (byte) total;
        out[8] = 64;                            // TTL
        out[9] = 1;                             // ICMP

        // 源地址必须是原报文的目的地址：客户端内核按「对端」匹配 socket，
        // 换成别的地址会被当成无关报文丢掉。
        System.arraycopy(pkt, 16, out, 12, 4);
        System.arraycopy(pkt, 12, out, 16, 4);

        int csum = DnsPacket.ipChecksum(out, 0, 20);
        out[10] = (byte) (csum >> 8);
        out[11] = (byte) csum;

        int ic = 20;
        out[ic] = (byte) TYPE_UNREACHABLE;
        out[ic + 1] = (byte) code;
        out[ic + 2] = 0;                        // 校验和先置零再算
        out[ic + 3] = 0;
        out[ic + 4] = 0;                        // 未使用，协议要求全零
        out[ic + 5] = 0;
        out[ic + 6] = 0;
        out[ic + 7] = 0;

        System.arraycopy(pkt, 0, out, ic + 8, quote);

        int icmpSum = DnsPacket.ipChecksum(out, ic, 8 + quote);
        out[ic + 2] = (byte) (icmpSum >> 8);
        out[ic + 3] = (byte) icmpSum;
        return out;
    }

    /* ---------------- 供测试与诊断使用 ---------------- */

    public static int type(byte[] b) {
        return b == null || b.length < 21 ? -1 : (b[20] & 0xff);
    }

    public static int code(byte[] b) {
        return b == null || b.length < 22 ? -1 : (b[21] & 0xff);
    }

    /** 校验一段数据的两补码和是否为 0，用于验证自己造的校验和对不对。 */
    public static boolean checksumOk(byte[] b, int off, int len) {
        long sum = 0;
        for (int i = 0; i < len; i += 2) {
            int hi = b[off + i] & 0xff;
            int lo = (i + 1 < len) ? (b[off + i + 1] & 0xff) : 0;
            sum += (hi << 8) | lo;
        }
        while ((sum >> 16) != 0) sum = (sum & 0xffff) + (sum >> 16);
        return (sum & 0xffff) == 0xffff;
    }
}
