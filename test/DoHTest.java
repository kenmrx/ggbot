import com.ggbot.app.DoH;

/** DoH：端点校验、IP 字面量识别、应答形状校验（HTTP 部分留到模拟器实测）。 */
public class DoHTest {

    private static int pass = 0;

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("PASS  " + name);
        } else {
            System.out.println("FAIL  " + name);
        }
    }

    public static void main(String[] args) {
        check("IPv4 字面量识别", DoH.isIpLiteral("223.5.5.5"));
        check("IPv6 字面量识别", DoH.isIpLiteral("2606:4700:4700::1111"));
        check("方括号 IPv6 识别", DoH.isIpLiteral("[2606:4700:4700::1111]"));
        check("域名不是字面量", !DoH.isIpLiteral("dns.alidns.com"));
        check("超 255 段拒绝", !DoH.isIpLiteral("300.1.1.1"));
        check("三段拒绝", !DoH.isIpLiteral("1.1.1"));
        check("带字母段拒绝", !DoH.isIpLiteral("1.1.1.1x"));
        check("空串拒绝", !DoH.isIpLiteral(""));
        check("null 拒绝", !DoH.isIpLiteral(null));

        check("端点 host 提取", "223.5.5.5".equals(DoH.hostOf("https://223.5.5.5/dns-query")));
        check("IP 端点通过", DoH.endpointOk("https://223.5.5.5/dns-query"));
        check("域名端点拒绝（防解析环路）", !DoH.endpointOk("https://dns.alidns.com/dns-query"));
        check("明文 http 端点拒绝", !DoH.endpointOk("http://223.5.5.5/dns-query"));
        check("null 端点拒绝", !DoH.endpointOk(null));

        byte[] resp = new byte[32];
        resp[2] = (byte) 0x80;
        check("应答形状通过", DoH.looksDns(resp));
        check("查询字节拒绝", !DoH.looksDns(new byte[32]));
        check("短包拒绝", !DoH.looksDns(new byte[8]));
        check("null 拒绝", !DoH.looksDns(null));

        check("短查询直接拒", DoH.query(new byte[4], "https://223.5.5.5/dns-query") == null);
        check("非法端点直接拒", DoH.query(new byte[32], "https://dns.example.com/dns-query") == null);

        System.out.println("通过 " + pass + " 项");
    }
}
