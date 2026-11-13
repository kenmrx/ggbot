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

        // viewId 形态：马克水印相机广告关闭钮（无文字小图 ×，文本路径接不住）
        check("ad_close_btn 命中", SkipMatcher.isAdCloseId("com.ai.marki:id/ad_close_btn"));
        check("native_ad_close 命中", SkipMatcher.isAdCloseId("com.xxx:id/native_ad_close"));
        check("adClose 命中（大小写不敏感）", SkipMatcher.isAdCloseId("com.app:id/AdClose"));
        check("splash_ad_close 命中", SkipMatcher.isAdCloseId("com.app:id/splash_ad_close"));
        check("null 拒收", !SkipMatcher.isAdCloseId(null));
        check("空串拒收", !SkipMatcher.isAdCloseId(""));
        check("无 close 拒收", !SkipMatcher.isAdCloseId("com.app:id/ad_banner"));
        check("纯 close 无广告语境拒收", !SkipMatcher.isAdCloseId("com.app:id/close_btn"));
        check("close_page 拒收", !SkipMatcher.isAdCloseId("com.app:id/close_page"));

        // 红果/抖音专项（2026-11-13）：穿山甲 tt_ 前缀关闭钮 + 跳过钮 id 形态
        check("tt_ad_close 命中（穿山甲关闭钮）", SkipMatcher.isAdCloseId("com.phoenix.read:id/tt_ad_close"));
        check("tt_splash_close 命中", SkipMatcher.isAdCloseId("com.app:id/tt_splash_close"));
        check("tt_close_no_ctx 拒收（tt_ 前缀但无 skip/count 不算关闭钮）", !SkipMatcher.isAdCloseId("com.app:id/tt_image"));
        check("splash_ad_skip 命中（抖音开屏跳过钮 id）", SkipMatcher.isAdSkipId("com.ss.android.ugc.aweme:id/splash_ad_skip"));
        check("tt_skip 命中（穿山甲跳过）", SkipMatcher.isAdSkipId("com.phoenix.read:id/tt_skip"));
        check("ad_count_down 命中（倒计时跳过）", SkipMatcher.isAdSkipId("com.app:id/ad_count_down"));
        check("business_jump 拒收（无广告语境）", !SkipMatcher.isAdSkipId("com.app:id/business_jump"));
        check("null 拒收 isAdSkipId", !SkipMatcher.isAdSkipId(null));
        check("空串拒收 isAdSkipId", !SkipMatcher.isAdSkipId(""));
        check("纯 skip 无语境拒收", !SkipMatcher.isAdSkipId("com.app:id/skip_btn"));

        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}
