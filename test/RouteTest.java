import com.ggbot.app.DnsPacket;
import com.ggbot.app.FakeIp;
import com.ggbot.app.Icmp;
import com.ggbot.app.MitmBypass;

/**
 * 「解密链路被绕过」这一组问题的验证。
 *
 * 这一类问题最讨厌的地方是：它不会报错，也不会让界面变红。
 * 应用直接走 IPv6 或 QUIC 出去了，DNS 统计照常增长，解密计数也照常增长，
 * 只有广告真的被加载了——而用户看到的是「这个 App 好像没拦住」。
 *
 * 四组：
 *   1 ICMP 端口不可达   把 QUIC 挡回去，逼客户端立刻回落 TCP
 *   2 AAAA 压成 NODATA  把应用从 IPv6 拉回 IPv4，否则隧道形同虚设
 *   3 自动直通          证书固定站点不再反复白屏，十分钟后自动重试
 *   4 地址分流          虚拟池边界必须精确，别把 DNS 自己的地址也当成 QUIC
 */
public class RouteTest {

    static int pass = 0;
    static int fail = 0;

    static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  ok   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }

    public static void main(String[] args) throws Exception {
        icmp();
        aaaa();
        bypass();
        pool();
        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }

    /* ---------------- 1. ICMP 端口不可达 ---------------- */

    static void icmp() {
        byte[] pkt = udp("10.0.0.9", "198.18.1.7", 51234, 443, 32);
        byte[] icmp = Icmp.portUnreachable(pkt, pkt.length);

        check("能生成 ICMP 报文", icmp != null);
        int total = 20 + 8 + 28;                       // 外层头 + ICMP 头 + 引用（20+8）
        check("总长 = 外层 20 + ICMP 8 + 引用 28", icmp.length == total);
        check("外层是 IPv4", (icmp[0] & 0xff) == 0x45);
        check("外层协议号是 ICMP", (icmp[9] & 0xff) == 1);
        check("类型 3 目的不可达", Icmp.type(icmp) == 3);
        check("代码 3 端口不可达", Icmp.code(icmp) == 3);

        // 源地址必须是原报文的目的地址，否则客户端内核不认这个差错
        check("源 = 原目的地址", ipAt(icmp, 12).equals("198.18.1.7"));
        check("目的 = 原源地址", ipAt(icmp, 16).equals("10.0.0.9"));
        check("外层 IP 校验和正确", Icmp.checksumOk(icmp, 0, 20));
        check("ICMP 校验和正确", Icmp.checksumOk(icmp, 20, icmp.length - 20));

        boolean quoted = true;
        for (int i = 0; i < 28; i++) {
            if (icmp[28 + i] != pkt[i]) {
                quoted = false;
                break;
            }
        }
        check("原 IP 头与 UDP 头逐字节保留", quoted);

        // 带 IP 选项（ihl=24）：引用要跟着变长，否则内核会把选项字段当成 UDP 头
        byte[] opt = udp("10.0.0.9", "198.18.2.3", 1234, 443, 16);
        byte[] o = new byte[opt.length + 4];
        o[0] = 0x46;                                   // ihl = 6
        System.arraycopy(opt, 1, o, 1, 3);
        o[2] = (byte) (o.length >> 8);
        o[3] = (byte) o.length;
        System.arraycopy(opt, 4, o, 4, 8);
        o[10] = 0;
        o[11] = 0;
        int cs = DnsPacket.ipChecksum(o, 0, 24);
        o[10] = (byte) (cs >> 8);
        o[11] = (byte) cs;
        System.arraycopy(opt, 20, o, 24, opt.length - 20);
        byte[] oi = Icmp.portUnreachable(o, o.length);
        check("带 IP 选项时引用 32 字节", oi != null && oi.length == 20 + 8 + 32);

        check("非 IPv4 返回 null", Icmp.portUnreachable(new byte[]{(byte) 0x60, 0, 0, 0}, 4) == null);
        check("长度不足返回 null", Icmp.portUnreachable(pkt, 10) == null);
        check("ICMP 总长不超过 576", icmp.length <= 576);

        // 空载荷的 UDP 也要能造出来：QUIC 首包通常有载荷，但别假设
        byte[] bare = udp("10.0.0.9", "198.18.1.7", 1, 443, 0);
        byte[] bi = Icmp.portUnreachable(bare, bare.length);
        check("零载荷 UDP 也能回", bi != null && Icmp.code(bi) == 3);
    }

    /* ---------------- 2. AAAA 压成 NODATA ---------------- */

    static void aaaa() throws Exception {
        byte[] q = query("www.example.com", 28);
        byte[] r = DnsPacket.buildError(q, 0, q.length, 0);
        check("AAAA 响应可构造", r != null);
        check("QDCOUNT = 1", ((r[4] & 0xff) << 8 | (r[5] & 0xff)) == 1);
        check("ANCOUNT = 0（NODATA）", ((r[6] & 0xff) << 8 | (r[7] & 0xff)) == 0);
        check("RCODE = 0（NOERROR）", (r[3] & 0x0f) == 0);
        check("事务 ID 原样带回", r[0] == q[0] && r[1] == q[1]);
        int qe = DnsPacket.questionEnd(r, 0, r.length);
        check("问题段完整保留", qe == q.length);
        check("QTYPE 仍是 28", DnsPacket.qtype(r, qe) == 28);
        check("响应比查询长不了（没有塞地址）", r.length == q.length);

        // A 查询的 NODATA 也顺带确认：buildError 不挑类型
        byte[] q4 = query("www.example.com", 1);
        byte[] r4 = DnsPacket.buildError(q4, 0, q4.length, 0);
        check("A 查询的 NODATA 同样合法", r4 != null && (r4[7] & 0xff) == 0);
    }

    /* ---------------- 3. 自动直通 ---------------- */

    static void bypass() {
        MitmBypass.clear();
        MitmBypass.clock = 1_000_000L;

        MitmBypass.failed("pinned.example.com");
        check("一次失败不直通", !MitmBypass.bypassed("pinned.example.com"));

        MitmBypass.failed("pinned.example.com");
        check("两次失败进入直通", MitmBypass.bypassed("pinned.example.com"));
        check("名单里能看到", MitmBypass.size() == 1
                && "pinned.example.com".equals(MitmBypass.active().get(0)));
        check("别的域名不受影响", !MitmBypass.bypassed("other.example.com"));

        // 十分钟后自动重试
        MitmBypass.clock += 10 * 60 * 1000L + 1;
        check("到期后恢复解密", !MitmBypass.bypassed("pinned.example.com"));
        check("到期后名单清空", MitmBypass.size() == 0);

        // 失败计数也有窗口：隔太久的两次失败不该累计
        MitmBypass.clear();
        MitmBypass.clock += 1;
        MitmBypass.failed("slow.example.com");
        MitmBypass.clock += 11 * 60 * 1000L;
        MitmBypass.failed("slow.example.com");
        check("超出窗口的失败不累计", !MitmBypass.bypassed("slow.example.com"));

        // 解密成功会把计数清掉：这个站点其实能解
        MitmBypass.clear();
        MitmBypass.failed("ok.example.com");
        MitmBypass.ok("ok.example.com");
        MitmBypass.failed("ok.example.com");
        check("成功一次即重置计数", !MitmBypass.bypassed("ok.example.com"));

        // 直通期内即使握手「成功」也不该把记录抹掉（那时根本没走解密）
        MitmBypass.failed("stay.example.com");
        MitmBypass.failed("stay.example.com");
        MitmBypass.ok("stay.example.com");
        check("直通期内不被成功回调清掉", MitmBypass.bypassed("stay.example.com"));

        MitmBypass.clear();
        check("清空后全部恢复", MitmBypass.size() == 0);
        MitmBypass.clock = 0;
    }

    /* ---------------- 4. 虚拟池边界 ---------------- */

    static void pool() {
        check("198.18.1.1 在池内", FakeIp.inPool(DnsPacket.toIp("198.18.1.1")));
        check("198.18.255.254 在池内", FakeIp.inPool(DnsPacket.toIp("198.18.255.254")));
        check("DNS 自身地址不在池内", !FakeIp.inPool(DnsPacket.toIp(DnsPacket.DNS_IP)));
        check("隧道地址不在池内", !FakeIp.inPool(DnsPacket.toIp("198.18.0.2")));
        check("公网地址不在池内", !FakeIp.inPool(DnsPacket.toIp("223.5.5.5")));
        check("同号但不同段的地址不在池内", !FakeIp.inPool(DnsPacket.toIp("198.19.1.1")));

        // 分配出来的地址必须落在池里，否则它发出去的 QUIC 不会被拦
        String ip = FakeIp.ipFor("probe.example.com");
        check("分配地址在池内", FakeIp.inPool(DnsPacket.toIp(ip)));
        check("反查回原域名", "probe.example.com".equals(FakeIp.hostFor(ip)));
        check("同一域名复用同一地址", ip.equals(FakeIp.ipFor("probe.example.com")));
        FakeIp.clear();
    }

    /* ---------------- 报文构造 ---------------- */

    /** 造一条 IPv4/UDP 报文，载荷填 0。 */
    static byte[] udp(String src, String dst, int srcPort, int dstPort, int payload) {
        int total = 20 + 8 + payload;
        byte[] p = new byte[total];
        p[0] = 0x45;
        p[2] = (byte) (total >> 8);
        p[3] = (byte) total;
        p[8] = 64;
        p[9] = 17;
        System.arraycopy(DnsPacket.toIp(src), 0, p, 12, 4);
        System.arraycopy(DnsPacket.toIp(dst), 0, p, 16, 4);
        int cs = DnsPacket.ipChecksum(p, 0, 20);
        p[10] = (byte) (cs >> 8);
        p[11] = (byte) cs;
        p[20] = (byte) (srcPort >> 8);
        p[21] = (byte) srcPort;
        p[22] = (byte) (dstPort >> 8);
        p[23] = (byte) dstPort;
        int ulen = 8 + payload;
        p[24] = (byte) (ulen >> 8);
        p[25] = (byte) ulen;
        return p;
    }

    static String ipAt(byte[] b, int off) {
        return (b[off] & 0xff) + "." + (b[off + 1] & 0xff) + "."
                + (b[off + 2] & 0xff) + "." + (b[off + 3] & 0xff);
    }

    /** 造一个指定类型的查询（1=A，28=AAAA）。 */
    static byte[] query(String domain, int type) {
        byte[] name = domain.getBytes(java.nio.charset.Charset.forName("ISO-8859-1"));
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(0x5a);
        o.write(0x01);
        o.write(0x01);
        o.write(0x00);
        o.write(0);
        o.write(1);
        for (int i = 0; i < 6; i++) o.write(0);
        int start = 0;
        for (int i = 0; i <= name.length; i++) {
            if (i == name.length || name[i] == '.') {
                int l = i - start;
                o.write(l);
                o.write(name, start, l);
                start = i + 1;
            }
        }
        o.write(0);
        o.write(0);
        o.write(type);
        o.write(0);
        o.write(1);
        return o.toByteArray();
    }
}
