import com.ggbot.app.A11y;

/** A11y：无障碍列表识别、合并、授权命令文案。 */
public class A11yTest {

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
        check("null 列表不算启用", !A11y.contains(null));
        check("空列表不算启用", !A11y.contains(""));
        check("平铺写法认得出", A11y.contains("com.ggbot.app/.SkipService"));
        check("混在多项里也认得出",
                A11y.contains("com.a/.B:com.ggbot.app/.SkipService:com.c/.D"));
        check("完整类名写法也认", A11y.contains("com.ggbot.app/com.ggbot.app.SkipService"));
        check("别的应用不算", !A11y.contains("com.other/.Svc"));
        check("前缀相近不算", !A11y.contains("com.ggbot.app/.SkipServiceX"));
        check("带空格也认", A11y.contains(" com.ggbot.app/.SkipService "));

        check("空列表合并就是自己", A11y.SVC.equals(A11y.merge(null)));
        check("空白列表合并也是自己", A11y.SVC.equals(A11y.merge("  ")));
        check("已有别的服务时追加",
                "com.a/.B:com.ggbot.app/.SkipService".equals(A11y.merge("com.a/.B")));
        check("已在列表里不重复追加",
                "com.a/.B:com.ggbot.app/.SkipService".equals(
                        A11y.merge("com.a/.B:com.ggbot.app/.SkipService")));

        check("开过又被揭且有权才写回", A11y.shouldRestore(true, false, true));
        check("没开过不替用户开", !A11y.shouldRestore(false, false, true));
        check("现在还在不动", !A11y.shouldRestore(true, true, true));
        check("没权限不写", !A11y.shouldRestore(true, false, false));

        check("授权命令含 pm grant", A11y.GRANT_CMD.contains("pm grant"));
        check("授权命令指向本包",
                A11y.GRANT_CMD.contains("com.ggbot.app")
                        && A11y.GRANT_CMD.contains("WRITE_SECURE_SETTINGS"));


        // 三态：没开过 / 开着 / 开着被摘
        check("没开过且现在没开 = NEVER", A11y.stateOf(false, false) == A11y.ST_NEVER);
        check("开过且现在开着 = ON", A11y.stateOf(true, true) == A11y.ST_ON);
        check("开过但被摘 = REMOVED", A11y.stateOf(false, true) == A11y.ST_REMOVED);
        check("首次开也能认成 ON", A11y.stateOf(true, false) == A11y.ST_ON);
        check("开着且有写权限说可自动补回",
                A11y.stateTextOf(A11y.ST_ON, 0, true).contains("可自动补回"));
        check("开着没写权限不吹牛",
                A11y.stateTextOf(A11y.ST_ON, 0, false).equals("已启用"));
        check("被摘一次说被摘", A11y.stateTextOf(A11y.ST_REMOVED, 1, false).contains("已被系统摘掉"));
        check("被摘两次以上点名反复", A11y.stateTextOf(A11y.ST_REMOVED, 3, false).contains("反复关闭")
                && A11y.stateTextOf(A11y.ST_REMOVED, 3, false).contains("3"));
        check("从没开过提示去开", A11y.stateTextOf(A11y.ST_NEVER, 0, false).contains("未开启"));

        // 僵尸绑定：名单还在、事件却断了 —— ContentObserver 完全看不见这一种
        check("连着·在名单·屏亮·事件断了 = 僵尸", A11y.looksStale(true, true, true, false));
        check("连着·在名单·屏亮·有事件 = 正常", !A11y.looksStale(true, true, true, true));
        check("没连上不算僵尸（那是没开）", !A11y.looksStale(false, true, true, false));
        check("不在名单不算僵尸（那是被摘）", !A11y.looksStale(true, false, true, false));
        check("息屏时事件安静不算僵尸", !A11y.looksStale(true, true, false, false));

        // 第四态：僵尸要在界面上看得见，否则用户只会反复拨开关
        check("开着且无响应 = STALLED", A11y.stateOf(true, true, true) == A11y.ST_STALLED);
        check("开着且有响应 = ON", A11y.stateOf(true, true, false) == A11y.ST_ON);
        check("不在名单时 stale 无意义，仍按被摘",
                A11y.stateOf(false, true, true) == A11y.ST_REMOVED);
        check("僵尸态有写权限说正在重绑",
                A11y.stateTextOf(A11y.ST_STALLED, 0, true).contains("正在尝试重绑"));
        check("僵尸态没写权限说点此重开",
                A11y.stateTextOf(A11y.ST_STALLED, 0, false).contains("点此重开"));
        check("僵尸态不谎报已启用",
                !A11y.stateTextOf(A11y.ST_STALLED, 0, true).equals("已启用"));

        // remove：重绑前要先把自己摘出来，且绝不能动用户别的无障碍服务
        check("空列表 remove 得空串", "".equals(A11y.remove(null)) && "".equals(A11y.remove("  ")));
        check("remove 掉自己后剩别的",
                "com.a/.B".equals(A11y.remove("com.a/.B:com.ggbot.app/.SkipService")));
        check("只有自己时 remove 得空串", "".equals(A11y.remove(A11y.SVC)));
        check("remove 认完整类名写法",
                "com.a/.B".equals(A11y.remove("com.ggbot.app/com.ggbot.app.SkipService:com.a/.B")));
        check("remove 不碰前缀相近的", "com.ggbot.app/.SkipServiceX".equals(
                A11y.remove("com.ggbot.app/.SkipServiceX")));
        check("remove 后再 merge 回到原状",
                ("com.a/.B:" + A11y.SVC).equals(A11y.merge(A11y.remove("com.a/.B:" + A11y.SVC))));

        // Shizuku 四态：没装 / 装了没跑 / 跑了没授权 / 就绪
        check("没装 = ST_NONE",
                com.ggbot.app.ShizukuHeal.stateOf(false, false, false)
                        == com.ggbot.app.ShizukuHeal.ST_NONE);
        check("装了但 Binder 没活 = NOT_RUNNING",
                com.ggbot.app.ShizukuHeal.stateOf(true, false, false)
                        == com.ggbot.app.ShizukuHeal.ST_NOT_RUNNING);
        check("Binder 活了没授权 = NOT_GRANTED",
                com.ggbot.app.ShizukuHeal.stateOf(true, true, false)
                        == com.ggbot.app.ShizukuHeal.ST_NOT_GRANTED);
        check("Binder 活且已授权 = READY",
                com.ggbot.app.ShizukuHeal.stateOf(true, true, true)
                        == com.ggbot.app.ShizukuHeal.ST_READY);
        check("就绪文案说会自动写回",
                com.ggbot.app.ShizukuHeal.textOf(com.ggbot.app.ShizukuHeal.ST_READY).contains("自动写回"));
        check("没授权文案指向 Shizuku 授权",
                com.ggbot.app.ShizukuHeal.textOf(com.ggbot.app.ShizukuHeal.ST_NOT_GRANTED).contains("授权"));
        check("没运行文案让用户先启动",
                com.ggbot.app.ShizukuHeal.textOf(com.ggbot.app.ShizukuHeal.ST_NOT_RUNNING).contains("启动"));
        check("没装文案点明可免电脑",
                com.ggbot.app.ShizukuHeal.textOf(com.ggbot.app.ShizukuHeal.ST_NONE).contains("免电脑"));

        // putSecure 走的是「server 起 settings 子进程」——argv 直传 exec、不过 shell。
        // 这里钉住命令形状：历史上第一版在这里发手搓 Parcel（协议是猜的，真机必败），
        // 一旦有人再改回字符串拼接 shell 或改坏参数顺序，这两组断言立刻翻红。
        String[] cmd = com.ggbot.app.ShizukuHeal.cmdFor("enabled_accessibility_services", "a/.B");
        check("命令是 settings put secure key value 五段 argv",
                cmd.length == 5 && "settings".equals(cmd[0]) && "put".equals(cmd[1])
                        && "secure".equals(cmd[2]) && "enabled_accessibility_services".equals(cmd[3])
                        && "a/.B".equals(cmd[4]));
        String[] nasty = com.ggbot.app.ShizukuHeal.cmdFor("k", "a b;c&d|e'f\"g$h");
        check("恶意 value 原样进 argv 不被拆分（不过 shell）",
                nasty.length == 5 && "a b;c&d|e'f\"g$h".equals(nasty[4]));
        check("key 里带点号也原样保留",
                "enabled_accessibility_services".equals(
                        com.ggbot.app.ShizukuHeal.cmdFor("enabled_accessibility_services", "v")[3]));

        // 写回路径依赖 Android 的 Parcel（桌面 JVM 是 stub），只能真机验，
        // 这里只钉住四态判定，不碰 IO
        check("Binder 没活时不算就绪",
                com.ggbot.app.ShizukuHeal.stateOf(true, false, false)
                        != com.ggbot.app.ShizukuHeal.ST_READY);
        check("四态互不重叠",
                com.ggbot.app.ShizukuHeal.ST_NONE != com.ggbot.app.ShizukuHeal.ST_READY
                        && com.ggbot.app.ShizukuHeal.ST_NOT_RUNNING
                        != com.ggbot.app.ShizukuHeal.ST_NOT_GRANTED);

        System.out.println("\n通过 " + pass + " 项");
        if (pass != 57) System.exit(1);
    }
}
