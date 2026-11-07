import com.ggbot.app.DnsBlock;

/**
 * DNS 拦截记录的结构化验证。
 *
 * 这块的价值全在「能不能从一条记录直接走到一个动作」。
 * 如果 line() 把查询域名和 CNAME 跳转目标糊成一个字符串，
 * 界面就没法把 host 单独取出来交给白名单——
 * 用户点「放行」，加进去的会是 `metrics.pandora.com → sc.omtrdc.net` 这种根本不是域名的东西，
 * 然后他会在白名单里看到一行乱码，并且什么也没放行。
 *
 * 所以下面这些断言盯的都是「两个域名有没有被混在一起」。
 */
public class DnsBlockTest {

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

    public static void main(String[] args) {
        plain();
        cname();
        formatting();
        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }

    static void plain() {
        DnsBlock b = DnsBlock.rule("hm.baidu.com", "订阅/内置域名库");
        check("普通拦截记为 KIND_RULE", b.kind == DnsBlock.KIND_RULE);
        check("普通拦截不算 CNAME", !b.cname());
        check("host 保持原样", "hm.baidu.com".equals(b.host));
        check("普通拦截没有跳转目标", b.via == null);
        check("依据被保留", "订阅/内置域名库".equals(b.reason));

        DnsBlock n = DnsBlock.rule("x.com", null);
        check("依据可以为空", n.reason == null);
        check("依据为空也能出详细说明", n.detail() != null && n.detail().contains("x.com"));
    }

    static void cname() {
        DnsBlock b = DnsBlock.cname("metrics.pandora.com", "pandora.com.d1.sc.omtrdc.net");
        check("隐身记为 KIND_CNAME", b.kind == DnsBlock.KIND_CNAME);
        check("隐身标记为真", b.cname());
        // 这一条是整个类里最重要的：查询域名与跳转目标必须是两个字段
        check("host 不是拼接串", "metrics.pandora.com".equals(b.host));
        check("跳转目标单独存放",
                "pandora.com.d1.sc.omtrdc.net".equals(b.via));
        check("host 里不含箭头", b.host.indexOf('→') < 0 && b.host.indexOf('⇢') < 0);

        String d = b.detail();
        check("说明里两个域名都在", d.contains("metrics.pandora.com") && d.contains("sc.omtrdc.net"));
        check("说明解释了为什么算追踪", d.contains("不在任何名单"));
    }

    static void formatting() {
        DnsBlock b = DnsBlock.rule("ad.example.com", "通配");
        String line = b.line();
        check("行首是时间", line.matches("^\\d{2}:\\d{2}:\\d{2}.*"));
        check("普通拦截行标「拦截」", line.contains("拦截"));
        check("行内含域名", line.contains("ad.example.com"));
        check("普通拦截行不含箭头", line.indexOf('→') < 0);

        DnsBlock c = DnsBlock.cname("a.com", "b.com");
        String cl = c.line();
        check("隐身行标「隐身」", cl.contains("隐身"));
        check("隐身行带跳转目标", cl.contains("a.com → b.com"));

        check("时间文本长度固定", b.timeText().length() == 8);
        check("长域名不截断（交给界面省略）",
                DnsBlock.rule("very.long.sub.domain.example.com", null).line()
                        .contains("very.long.sub.domain.example.com"));
    }
}
