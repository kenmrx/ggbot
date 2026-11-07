import com.ggbot.app.A11y;
import com.ggbot.app.Battery;
import com.ggbot.app.Cond;
import com.ggbot.app.CaStore;
import com.ggbot.app.CertGuide;
import com.ggbot.app.SelfCheck;
import com.ggbot.app.Mitm;
import com.ggbot.app.A11yWatch;
import com.ggbot.app.Power;
import com.ggbot.app.SkipBudget;
import com.ggbot.app.Crash;
import com.ggbot.app.Export;
import com.ggbot.app.Feeds;
import com.ggbot.app.Fold;
import com.ggbot.app.NetSwitch;
import com.ggbot.app.LogView;
import com.ggbot.app.Net;
import com.ggbot.app.Notif;
import com.ggbot.app.Sni;
import com.ggbot.app.Pause;
import com.ggbot.app.QueryLog;
import com.ggbot.app.Rules;
import com.ggbot.app.Tone;
import com.ggbot.app.Vendor;
import com.ggbot.app.Alt;

/** 第一批吸收项的纯逻辑：暂停时长与恢复、网络切换去抖、条件请求判定。 */
public class OpsTest {

    private static int pass = 0;

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("PASS  " + name);
        } else {
            System.out.println("FAIL  " + name);
        }
    }

    public static void main(String[] args) {
        long now = 1_000_000L;

        check("暂停 15 分钟后到点", Pause.until(now, 15) == now + 15 * 60_000L);
        check("非法时长回落到默认", Pause.until(now, 0) == now + Pause.DEFAULT_MINUTES * 60_000L);
        check("负数时长回落到默认", Pause.until(now, -3) == now + Pause.DEFAULT_MINUTES * 60_000L);
        check("提前一分钟仍在暂停", Pause.active(now + 60_000L, now));
        check("到点即失效", !Pause.active(now, now));
        check("过点也失效", !Pause.active(now - 1, now));
        check("没暂停时文案是未暂停", "未暂停".equals(Pause.text(now, 0)));
        check("暂停中文案含时刻", Pause.text(now, now + 600_000L).startsWith("暂停至 "));
        check("恢复时刻是 4 位 HH:mm", Pause.resumeLabel(now + 600_000L).matches("\\d{2}:\\d{2}"));

        check("无障碍列表命中", A11y.contains("a:b:com.ggbot.app/.SkipService:c"));
        check("无障碍列表未命中", !A11y.contains("a:com.other/.Svc"));
        check("无障碍空列表", !A11y.contains(null));
        check("白名单文案：已加入", Battery.label(true).contains("已加入"));
        check("白名单文案：受限", Battery.label(false).contains("限制"));
        check("首次网络回调立刻处理", NetSwitch.shouldAct(now, 0));
        check("刚处理过的不重复处理", !NetSwitch.shouldAct(now, now));
        check("间隔够了才再处理", NetSwitch.shouldAct(now + NetSwitch.DEBOUNCE_MS, now));
        check("差 1ms 不处理", !NetSwitch.shouldAct(now + NetSwitch.DEBOUNCE_MS - 1, now));

        check("304 算新鲜", Cond.fresh(304));
        check("200 不算 304", !Cond.fresh(200));
        check("200 算成功", Cond.ok(200));
        check("空 ETag 不可用", !Cond.usable(""));
        check("null ETag 不可用", !Cond.usable(null));
        check("空白 ETag 不可用", !Cond.usable("   "));
        check("正常 ETag 可用", Cond.usable("abc123"));
        check("ETag 会去空白", "abc123".equals(Cond.ifNoneMatch("  abc123  ")));
        check("空 ETag 不发头", Cond.ifNoneMatch("") == null);
        check("修改时间会去空白", "Wed, 21 Oct 2015 07:28:00 GMT".equals(
                Cond.ifModifiedSince(" Wed, 21 Oct 2015 07:28:00 GMT ")));
        check("空修改时间不发头", Cond.ifModifiedSince(null) == null);

        String txt = Export.build(0L, "1.3.2", "引擎X", new String[]{"事件A"},
                new String[][]{{"12:00:00", "x.com", "拦截"}}, new String[]{"防护：运行中", null});
        check("导出含版本", txt.contains("版本：1.3.2"));
        check("导出含引擎", txt.contains("引擎：引擎X"));
        check("导出含诊断事件段", txt.contains("[诊断事件]") && txt.contains("事件A"));
        check("导出含查询日志段", txt.contains("[查询日志]") && txt.contains("x.com"));
        check("导出跳过 null 备注", !txt.contains("· null"));
        check("空日志写(无)", Export.build(0L, "v", "e", new String[0], new String[0][], new String[0])
                .contains("(无)"));
        check("文件名前缀后缀", Export.fileName(0L).startsWith("ggbot-diag-")
                && Export.fileName(0L).endsWith(".txt"));
        check("文件名带日期时分", Export.fileName(0L).matches("ggbot-diag-\\d{8}-\\d{4}\\.txt"));


        // ---- 日志折叠与筛选（思路来自算法助手Pro 2.0.4 的重复日志折叠 / 筛选）----
        QueryLog.add("dup.com", QueryLog.BLOCK);
        QueryLog.add("dup.com", QueryLog.BLOCK);
        QueryLog.add("dup.com", QueryLog.BLOCK);
        QueryLog.add("ok.com", QueryLog.ALLOW);
        Fold.Group[] gs = Fold.byHost(QueryLog.recent());
        Fold.Group dup = null;
        Fold.Group ok = null;
        for (Fold.Group g : gs) {
            if ("dup.com".equals(g.host)) dup = g;
            if ("ok.com".equals(g.host)) ok = g;
        }
        check("折叠后同域名只剩一组", gs.length == 2);
        check("同域名合并计数为 3", dup != null && dup.count == 3);
        check("单条记录计数为 1", ok != null && ok.count == 1);
        check("次数文案只在多次时出现",
                dup != null && dup.countText().equals(" \u00d73") && ok != null && ok.countText().isEmpty());
        check("折叠保留结果标签", dup != null && dup.label().equals("拦截")
                && ok != null && ok.label().equals("放行"));
        QueryLog.add("dup.com", QueryLog.ALLOW);
        check("同域名不同结果不合并", Fold.byHost(QueryLog.recent()).length == 3);
        check("筛选只留拦截", Fold.filter(QueryLog.recent(), QueryLog.BLOCK).length == 3);
        check("筛选只留放行", Fold.filter(QueryLog.recent(), QueryLog.ALLOW).length == 2);
        check("筛选只留解密为空", Fold.filter(QueryLog.recent(), QueryLog.DECRYPT).length == 0);
        QueryLog.Entry[] all = QueryLog.recent();
        check("不过滤时原样返回", Fold.filter(all, Fold.ANY) == all);
        check("空输入折叠为空", Fold.byHost(new QueryLog.Entry[0]).length == 0);
        check("空输入筛选不炸", Fold.filter(null, QueryLog.BLOCK).length == 0);


        // ---- 厂商识别与保活步骤（荣耀/华为重点，见 Vendor）----
        check("识别荣耀", Vendor.HONOR.equals(Vendor.brandOf("HONOR", "HONOR")));
        check("识别华为", Vendor.HUAWEI.equals(Vendor.brandOf("HUAWEI", "HUAWEI")));
        check("大小写不敏感", Vendor.HONOR.equals(Vendor.brandOf("honor", "BRT")));
        check("识别小米/OPPO/vivo", Vendor.XIAOMI.equals(Vendor.brandOf("Xiaomi", "Redmi"))
                && Vendor.OPPO.equals(Vendor.brandOf("OPPO", "realme"))
                && Vendor.VIVO.equals(Vendor.brandOf("vivo", "iQOO")));
        check("陌生厂商归 other", Vendor.OTHER.equals(Vendor.brandOf("samsung", "SM-G9")));
        check("空值不炸", Vendor.OTHER.equals(Vendor.brandOf(null, null)));
        check("荣耀华为四步", Vendor.steps(Vendor.HONOR).length == 4
                && Vendor.steps(Vendor.HUAWEI).length == 4);
        check("荣耀步骤点名受保护应用", Vendor.steps(Vendor.HONOR)[1].contains("受保护应用"));
        check("荣耀步骤点名应用启动管理", Vendor.steps(Vendor.HONOR)[0].contains("应用启动管理"));
        check("其他厂商三步", Vendor.steps(Vendor.OTHER).length == 3
                && Vendor.steps("samsung").length == 3);
        check("厂商名可读", "荣耀".equals(Vendor.name(Vendor.HONOR))
                && "华为".equals(Vendor.name(Vendor.HUAWEI)));

        // ---- 替代路径的结论文案（Shizuku 等价于 adb 授权；不开无障碍不影响拦截）----
        String alt1 = Alt.text(false, false);
        String alt2 = Alt.text(true, true);
        check("替代路径文案含 Shizuku", alt1.contains("Shizuku"));
        check("未装时不谎称已装", !alt1.contains("已安装") && alt2.contains("已安装"));
        check("说明 Shizuku 等价于 shell 权限", alt1.contains("shell") && alt1.contains("同一件事"));
        check("有写权限时说是已有授权", alt2.contains("adb 授权"));
        check("无写权限时指 adb 命令", alt1.contains("pm grant"));
        check("明确开关不能代开", alt1.contains("无法代劳"));
        check("明确拦截不受影响", alt1.contains("DNS 拦截不依赖无障碍"));
        check("Shizuku 包名固定", "moe.shizuku.privileged.api".equals(Alt.SHIZUKU_PKG));
        check("http 订阅判为明文", Feeds.insecure("http://a.com/x.txt"));
        check("https 订阅判为安全", !Feeds.insecure("https://a.com/x.txt"));
        check("大写协议也认", Feeds.insecure("HTTP://A.com/x.txt"));
        check("无协议不算明文", !Feeds.insecure("a.com/x.txt"));
        check("null 不炸", !Feeds.insecure(null));

        Rules.blockNow(null);
        Rules.blockNow("");
        check("空域名拦截不炸", true);
        Rules.blockNow("zzz.ggbot-test.com");
        check("当场拦截立即生效", Rules.isBlocked("zzz.ggbot-test.com"));
        check("大小写不敏感", Rules.isBlocked("ZZZ.GGBOT-TEST.COM"));

        check("正文对背景 >= 7:1", Tone.contrast(Tone.FG, Tone.BG) >= 7.0);
        check("次要文案 >= 4.5:1", Tone.contrast(Tone.MUTED, Tone.BG) >= 4.5);
        check("说明文字 >= 4.5:1", Tone.contrast(Tone.DIM, Tone.BG) >= 4.5);
        check("强调色 >= 4.5:1", Tone.contrast(Tone.ACCENT, Tone.BG) >= 4.5);
        check("DIM 已不是旧的 3.43:1", Tone.DIM != 0xFF5C6B63);
        check("触控基准是 48dp", Tone.TOUCH_DP == 48);

        check("API<33 不问通知权限", !Notif.shouldAsk(32, false, false));
        check("已授权不问", !Notif.shouldAsk(33, true, false));
        check("未授权且未问过才问", Notif.shouldAsk(33, false, false));
        check("问过就不再自动问", !Notif.shouldAsk(33, false, true));
        check("通知文案: 已开启", Notif.text(true, true).contains("已开启"));
        check("通知文案: 关闭引导", Notif.text(false, true).contains("去开启"));

        check("日志上限: 不足上限全显", LogView.visible(30, 120) == 30);
        check("日志上限: 超限只显上限", LogView.visible(300, 120) == 120);
        check("日志上限: 负数不炸", LogView.visible(-5, 120) == 0);
        check("日志截断标志", LogView.truncated(300, 120) && !LogView.truncated(100, 120));
        check("页脚说清未渲染数量", LogView.footer(300, 120).contains("180"));
        check("未截断时无页脚", LogView.footer(50, 120).isEmpty());

        check("在线+运行", Net.status(true, true).equals("防护已开启"));
        check("断网+运行要说清无网", Net.status(true, false).contains("无网络"));
        check("断网+停止也要说清", Net.status(false, false).contains("无网络"));
        check("在线+停止", Net.status(false, true).equals("防护已关闭"));

        check("崩溃描述含类名", Crash.describe(new IllegalStateException("boom")).contains("IllegalStateException"));
        check("崩溃描述含消息", Crash.describe(new RuntimeException("x1")).contains("x1"));
        check("空异常不炸", Crash.describe(null).contains("未知"));

        // SNI：用真实 ClientHello 前 120 字节验证（OpenSSL 生成，含 server_name=example.com）
        byte[] hello = hex("1603010200010001fc030365b0f15486e053e2f48df7d75028fd63f7ec9249afc883a0a1f712f64f16b7cf204478f542a87d17c2d77507bc22836a5d524743909fc47634373b1f921753b09c003e130213031301c02cc030009fcca9cca8ccaac02bc02f009ec024c028006bc023c0270067c00ac0140039c009c0130033009d009c003d003c0035002f00ff0100017500000010000e00000b6578616d706c652e636f6d");
        check("SNI 能取出 example.com", "example.com".equals(Sni.host(hello, 0, hello.length)));
        check("半包不算完整", !Sni.complete(hello, 0, 100));
        check("半包取不到 SNI", Sni.host(hello, 0, 100) == null);
        check("非 TLS 返回 null", Sni.host(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, 0, 9) == null);
        check("空数组不炸", Sni.host(new byte[0], 0, 0) == null);
        // SNI 首字符换成制表符：控制字符会污染下游（证书 CN、RealResolver 的 QNAME），
        // 必须拒收而不是带病往下传
        byte[] dirtySni = hello.clone();
        dirtySni[dirtySni.length - 11] = 0x09;
        check("SNI 带控制字符拒收", Sni.host(dirtySni, 0, dirtySni.length) == null);

        // CA 指纹：装进系统信任库后用户要靠它核对是哪一张
        try {
            CaStore.init();
            String fp = CaStore.fingerprint();
            check("CA 指纹是 SHA-256 格式", fp != null && fp.matches("([0-9A-F]{2}:){31}[0-9A-F]{2}"));
            String sf = CaStore.shortFingerprint();
            check("指纹缩写含省略号", sf != null && sf.contains("…"));
            check("指纹缩写比全长短", sf != null && fp != null && sf.length() < fp.length());
        } catch (Throwable t) {
            check("CA 指纹生成", false);
        }

        // 证书引导：状态不同，说的下一步不同
        CertGuide.State st = new CertGuide.State();
        check("未导出时指向导出", CertGuide.hint(st).contains("导出证书"));
        check("未导出时下一步是第0步", CertGuide.nextIndex(st) == 0);
        st.exported = true;
        check("已导出未装指向安全设置", CertGuide.hint(st).contains("安装证书"));
        check("已导出时下一步是第1步", CertGuide.nextIndex(st) == 1);
        st.userInstalled = true;
        check("用户证书装完要提醒 Android 7 限制", CertGuide.hint(st).contains("Android 7"));
        check("完成步骤数=2", CertGuide.doneCount(st) == 2);
        st.systemInstalled = true;
        check("全装好后指向验证", CertGuide.hint(st).contains("解密"));
        check("root 命令含目标路径", CertGuide.rootCommand("abcd").contains("/system/etc/security/cacerts/abcd.0"));
        check("hash 为空不给命令", CertGuide.rootCommand("").isEmpty());

        // SNI 覆盖隧道主机：CDN 共用 IP 时，隧道主机不等于证书名
        check("SNI 覆盖隧道主机", "example.com".equals(Mitm.serveName("cdn.example.net", hello)));
        check("无 SNI 用隧道主机", "cdn.example.net".equals(Mitm.serveName("cdn.example.net", new byte[0])));
        check("空首包用隧道主机", "a.com".equals(Mitm.serveName("a.com", null)));
        check("自检错误文案不空", SelfCheck.describe(null).startsWith("失败"));

        // 无障碍名单监听（吸收自 GKD）：键名写错就彻底监听不到，必须钉住
        check("监听的是 enabled_accessibility_services",
                "enabled_accessibility_services".equals(A11yWatch.KEY));
        check("断开时给警告文案", "无障碍已断开".equals(A11yWatch.describe(true, false)));
        check("恢复时给恢复文案", "无障碍已恢复".equals(A11yWatch.describe(false, true)));
        check("无变化不打扰", A11yWatch.describe(true, true) == null);
        check("磁贴文案说可用", "开屏跳过：可用".equals(A11yWatch.tileHint(true)));
        check("磁贴文案说断开", "开屏跳过：已断开".equals(A11yWatch.tileHint(false)));
        // 跳过预算（吸收自 GKD 的 actionMaximum / actionCd / matchTime）
        check("预算：全新页可点", SkipBudget.OK.equals(SkipBudget.decide(1000, 1000, 0, 0)));
        check("预算：点够上限就收手", SkipBudget.USED_UP.equals(
                SkipBudget.decide(2000, 1000, 1900, SkipBudget.MAX_CLICKS)));
        check("预算：冷却中不重复点", SkipBudget.COOLING.equals(
                SkipBudget.decide(1200, 1000, 1000, 1)));
        check("预算：超过时间窗收手", SkipBudget.EXPIRED.equals(
                SkipBudget.decide(1000 + SkipBudget.WINDOW_MS + 1, 1000, 0, 0)));
        check("预算：上限文案说明次数", SkipBudget.text(SkipBudget.USED_UP, 5).contains("上限 5"));

        System.out.println("\n通过 " + pass + " 项");
        // 省电模式四档判定（吸收用户诉求：只跳过 / 轻量只拦 DNS）
        check("标准档允许解密", Power.allowsMitm(Power.NORMAL) && Power.allowsTcp(Power.NORMAL));
        check("轻量档不做解密也不转 TCP",
                !Power.allowsMitm(Power.LIGHT) && !Power.allowsTcp(Power.LIGHT));
        check("轻量档仍要 VPN", Power.needsVpn(Power.LIGHT));
        check("只跳过档不开 VPN", !Power.needsVpn(Power.SKIP));
        check("轻量档文案说清拦不到正文广告", Power.detail(Power.LIGHT).contains("HTTPS"));
        check("只跳过文案说清 DNS 不拦", Power.detail(Power.SKIP).contains("DNS"));
        if (pass != 139) System.exit(1);
    }
}
