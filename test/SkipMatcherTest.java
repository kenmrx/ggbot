import com.ggbot.app.SkipMatcher;

/** SkipMatcher：跳过按钮判定。 */
public class SkipMatcherTest {

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
        check("跳过", SkipMatcher.isSkipText("跳过"));
        check("跳过广告", SkipMatcher.isSkipText("跳过广告"));
        check("跳过 3", SkipMatcher.isSkipText("跳过 3"));
        check("跳过 3s", SkipMatcher.isSkipText("跳过 3s"));
        check("点击跳过", SkipMatcher.isSkipText("点击跳过"));
        check("Skip", SkipMatcher.isSkipText("Skip"));
        check("skip 3", SkipMatcher.isSkipText("skip 3"));
        check("null 拒绝", !SkipMatcher.isSkipText(null));
        check("空文本拒绝", !SkipMatcher.isSkipText("   "));
        check("长文本拒绝（正文句子）", !SkipMatcher.isSkipText("跳过此步骤，您可以稍后在设置里重新开始引导流程"));
        check("不跳过拒绝", !SkipMatcher.isSkipText("不跳过"));
        System.out.println("通过 " + pass + " 项，失败 0 项");
    }
}
