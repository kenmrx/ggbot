import com.ggbot.app.SkipMatcher;

/**
 * 开屏跳过判定测试：覆盖 2026-11-07 真机样本（红果/马克等开屏按钮形态）。
 */
public class SkipMatcherTest {
    static int pass = 0;
    static int fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    public static void main(String[] args) {
        // 命中：真实按钮形态
        check("跳过", SkipMatcher.isSkipText("跳过"));
        check("跳过 3（带空格）", SkipMatcher.isSkipText("跳过 3"));
        check("跳过3（无空格）", SkipMatcher.isSkipText("跳过3"));
        check("跳过3s", SkipMatcher.isSkipText("跳过3s"));
        check("跳过广告", SkipMatcher.isSkipText("跳过广告"));
        check("跳过广告 5", SkipMatcher.isSkipText("跳过广告 5"));
        check("点击跳过", SkipMatcher.isSkipText("点击跳过"));
        check("Skip Ad", SkipMatcher.isSkipText("Skip Ad"));
        check("Skip 3s", SkipMatcher.isSkipText("Skip 3s"));
        check("跳过（尾部空格）", SkipMatcher.isSkipText("跳过  "));
        // 拒收：危险/无关形态
        check("不跳过 拒收", !SkipMatcher.isSkipText("不跳过"));
        check("无法跳过 拒收", !SkipMatcher.isSkipText("无法跳过"));
        check("跳过这一段精彩剧情（正文长句12字超限）拒收", !SkipMatcher.isSkipText("跳过这一段精彩剧情"));
        check("无法跳过 拒收（排除词）", !SkipMatcher.isSkipText("无法跳过"));
        check("空串拒收", !SkipMatcher.isSkipText(""));
        check("null 拒收", !SkipMatcher.isSkipText(null));
        check("纯×拒收（防误关正常弹窗）", !SkipMatcher.isSkipText("×"));
        check("关闭拒收", !SkipMatcher.isSkipText("关闭"));

        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}
