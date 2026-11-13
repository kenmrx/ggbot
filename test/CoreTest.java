import com.ggbot.app.DnsPacket;
import com.ggbot.app.Domains;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * GGBot 核心逻辑自检（纯 JVM，不需要安卓设备）。
 * 覆盖：QNAME 解析、NXDOMAIN / 0.0.0.0 应答、IPv4+UDP 封装与校验和、域名归一化、后缀匹配、真实规则库抽样。
 */
public class CoreTest {

    static int pass = 0;
    static int fail = 0;

    static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name);
        } else {
            fail++;
            System.out.println("  FAIL  " + name);
        }
    }

    public static void main(String[] args) throws Exception {
        String asset = args.length > 0 ? args[0] : "assets/blocklist.txt";

        System.out.println("[1] DNS 报文解析");
        byte[] q = DnsPacket.buildQuery("Ads.DoubleClick.NET");
        check("QNAME 解析并转小写", "ads.doubleclick.net".equals(
                DnsPacket.parseQuestion(q, 0, q.length)));
        int qe = DnsPacket.questionEnd(q, 0, q.length);
        check("questionEnd 落在问题段末尾", qe == q.length);
        check("QTYPE 读出为 A(1)", DnsPacket.qtype(q, qe) == 1);
        check("长度不足时不越界", DnsPacket.questionEnd(q, 0, 10) == -1);
        check("空报文返回 null", DnsPacket.parseQuestion(q, 0, 12) == null);

        System.out.println("[2] NXDOMAIN 应答");
        byte[] r = DnsPacket.buildError(q, 0, q.length, 3);
        check("应答长度等于问题段", r.length == q.length);
        check("QR=1", (r[2] & 0x80) != 0);
        check("rcode=3", (r[3] & 0x0f) == 3);
        check("RA=1", (r[3] & 0x80) != 0);
        check("ANCOUNT=0", r[6] == 0 && r[7] == 0);
        check("事务 ID 原样保留", r[0] == q[0] && r[1] == q[1]);

        System.out.println("[3] 0.0.0.0 应答");
        byte[] n = DnsPacket.buildNullAnswer(q, 0, q.length, 1);
        check("长度 = 问题段 + 16", n.length == q.length + 16);
        check("ANCOUNT=1", n[6] == 0 && n[7] == 1);
        check("回答 TYPE=A", n[qe + 2] == 0 && n[qe + 3] == 1);
        check("回答 CLASS=IN", n[qe + 4] == 0 && n[qe + 5] == 1);
        check("TTL=3600", (((n[qe + 8] & 0xff) << 8) | (n[qe + 9] & 0xff)) == 3600);
        check("RDLENGTH=4", n[qe + 11] == 4);
        check("RDATA=0.0.0.0", n[qe + 12] == 0 && n[qe + 13] == 0
                && n[qe + 14] == 0 && n[qe + 15] == 0);
        byte[] n6 = DnsPacket.buildNullAnswer(q, 0, q.length, 28);
        check("AAAA 走空 NOERROR", n6.length == q.length
                && (n6[3] & 0x0f) == 0 && n6[7] == 0);

        System.out.println("[4] IPv4 + UDP 封装");
        byte[] src = new byte[]{10, 0, 0, 7};
        byte[] pkt = DnsPacket.buildIpUdp(r, src, 34567);
        check("IP 总长字段 = 实际长度",
                (((pkt[2] & 0xff) << 8) | (pkt[3] & 0xff)) == pkt.length);
        check("协议字段 = UDP(17)", pkt[9] == 17);
        check("TTL = 64", pkt[8] == 64);
        check("源地址 = 198.18.0.1", (pkt[12] & 0xff) == 198 && (pkt[13] & 0xff) == 18
                && (pkt[14] & 0xff) == 0 && (pkt[15] & 0xff) == 1);
        check("目的地址 = 客户端", (pkt[16] & 0xff) == 10 && (pkt[19] & 0xff) == 7);
        check("源端口 = 53", (((pkt[20] & 0xff) << 8) | (pkt[21] & 0xff)) == 53);
        check("目的端口 = 客户端端口",
                (((pkt[22] & 0xff) << 8) | (pkt[23] & 0xff)) == 34567);
        check("UDP 长度字段正确",
                (((pkt[24] & 0xff) << 8) | (pkt[25] & 0xff)) == 8 + r.length);
        check("载荷与应答一致",
                Arrays.equals(Arrays.copyOfRange(pkt, 28, pkt.length), r));
        long sum = 0;
        for (int i = 0; i < 20; i += 2) sum += ((pkt[i] & 0xff) << 8) | (pkt[i + 1] & 0xff);
        while ((sum >> 16) != 0) sum = (sum & 0xffff) + (sum >> 16);
        check("IP 头校验和自洽（求和 = 0xFFFF）", sum == 0xffff);

        System.out.println("[5] 域名归一化");
        check("去协议/端口/路径", "ads.example.com".equals(
                Domains.normalize(" HTTPS://Ads.Example.com:8080/path?a=1 ")));
        check("去通配前缀", "a.com".equals(Domains.normalize("*.a.com")));
        check("尾部点号", "a.com".equals(Domains.normalize("a.com.")));
        check("拒绝 IP", Domains.normalize("0.0.0.0") == null);
        check("拒绝单标签", Domains.normalize("localhost") == null);
        check("拒绝空格", Domains.normalize("a b.com") == null);
        check("拒绝注释", Domains.normalize("#comment") == null);
        check("拒绝空串", Domains.normalize("   ") == null);

        System.out.println("[6] 后缀匹配");
        Set<String> s = new HashSet<>(Arrays.asList("ads.com"));
        check("自身命中", Domains.matches(s, "ads.com"));
        check("多级子域命中", Domains.matches(s, "a.b.ads.com"));
        check("相似域名不误伤", !Domains.matches(s, "xads.com"));
        check("空集合不命中", !Domains.matches(new HashSet<String>(), "ads.com"));
        check("null 安全", !Domains.matches(s, null));

        System.out.println("[7] 真实规则库");
        long t0 = System.nanoTime();
        Set<String> lib = new HashSet<>(131072);
        List<String> dirty = new ArrayList<>();
        int total = 0;
        BufferedReader br = new BufferedReader(new FileReader(asset), 65536);
        String line;
        while ((line = br.readLine()) != null) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            total++;
            String norm = Domains.normalize(line);
            if (norm == null || !norm.equals(line)) {
                if (dirty.size() < 5) dirty.add(line);
                continue;
            }
            lib.add(norm);
        }
        br.close();
        long ms = (System.nanoTime() - t0) / 1000000;
        System.out.println("  规则库 " + total + " 行 -> 有效 " + lib.size()
                + " 条，载入 " + ms + " ms");
        check("规则库行数与预期一致", total == 54678);
        check("全部行均为合法域名", dirty.isEmpty());
        check("含 doubleclick 域名", lib.contains("doubleclick.net"));

        List<String> sample = new ArrayList<>(lib);
        int hit = 0;
        int probe = Math.min(500, sample.size());
        for (int i = 0; i < probe; i++) {
            if (Domains.matches(lib, "probe." + sample.get(i))) hit++;
        }
        check("随机 500 条子域全部命中（" + hit + "/" + probe + "）", hit == probe);
        check("百度不在拦截库", !Domains.matches(lib, "www.baidu.com"));
        check("alipay 不在拦截库", !Domains.matches(lib, "mclient.alipay.com"));

        long t1 = System.nanoTime();
        int round = 200000;
        for (int i = 0; i < round; i++) {
            Domains.matches(lib, "a.b.c.example-domain.com");
        }
        double ns = (System.nanoTime() - t1) / (double) round;
        System.out.printf("  单次匹配平均 %.0f ns（%.2f 万次/秒）%n", ns, 10000 / ns);

        System.out.println("== 上游应答校验（缓存投毒防线）==");
        byte[] qid = new byte[12];
        qid[0] = 'A';
        qid[1] = 'B';
        qid[2] = 0x01;
        byte[] respOk = new byte[12];
        respOk[0] = 'A';
        respOk[1] = 'B';
        respOk[2] = (byte) 0x81;
        check("ID 匹配 + QR=1 接受", DnsPacket.validUpstreamResponse(qid, respOk));
        byte[] badId = respOk.clone();
        badId[0] = 'X';
        check("ID 不匹配拒绝（明文 UDP 可伪造）", !DnsPacket.validUpstreamResponse(qid, badId));
        byte[] notResp = respOk.clone();
        notResp[2] = 0x01;
        check("QR=0（查询）拒绝", !DnsPacket.validUpstreamResponse(qid, notResp));
        check("空应答拒绝（否则被当拦截哨兵误拦）", !DnsPacket.validUpstreamResponse(qid, new byte[0]));
        check("短于 DNS 头拒绝", !DnsPacket.validUpstreamResponse(qid, new byte[11]));
        check("null 全拒", !DnsPacket.validUpstreamResponse(qid, null)
                && !DnsPacket.validUpstreamResponse(null, respOk));

        System.out.println();
        System.out.println("结果：" + pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }
}
