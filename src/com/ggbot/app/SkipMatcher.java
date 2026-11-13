package com.ggbot.app;

import java.util.Locale;

/**
 * 开屏「跳过」按钮的判定（纯逻辑，桌面可测）。
 *
 * 真实开屏按钮形态（2026-11-07 真机样本归纳：红果/马克等）：
 *   跳过 / 跳过 3 / 跳过3s / 跳过广告 / 跳过广告 5 / 点击跳过 /
 *   Skip / Skip Ad / Skip 3s
 *
 * 判定规则：
 *   · 长度 ≤ 8 字符（真实按钮最长「跳过广告 5」=6 字；正文长句远超 8）
 *   · 含「跳过」或 skip（大小写不敏感）
 *   · 排除「不跳过」「无法跳过」；纯「×」「关闭」不算——
 *     关闭按钮可能是正常弹窗，乱点会误伤
 *     （真机关闭钮的 contentDescription 通常仍含"跳过"，那条路能接住；
 *      无文字的小图 × 走 isAdCloseId 的 viewId 路径，见下）
 */
public final class SkipMatcher {

    private SkipMatcher() {
    }

    /**
     * 广告关闭钮的 viewId 形态（2026-11-07 马克水印相机真机归纳：
     * 其广告是「无文字小图 ×」，文本路径永远接不住，只能按 resource-id 匹配）：
     *   ad_close_btn / native_ad_close / ad_close / close_btn_ad 等
     * 全部小写、包含 "ad" 与 "close" 的组合即视为广告关闭钮。
     * 误伤面：普通业务控件不会同时带 ad+close 命名，风险可忽略。
     *
     * 2026-11-13 红果/抖音专项增补（逆向 com.phoenix.read 7.4.5.32 与
     * com.ss.android.ugc.aweme 41.1.0 静态产物归纳）：
     *   抖音开屏：com.bytedance.ies.ugc.aweme.commercialize.splash.*，
     *   资源 id 常见 splash_ad_close / splash_close_btn / ad_skip_btn 形态；
     *   红果开屏：csj(穿山甲) 容器常带 tt_ad_close / tt_skip / ad_container_close。
     *   穿山甲系 id 统一前缀 tt_，与 ad/close/skip/splash 组合即命中。
     */
    public static boolean isAdCloseId(CharSequence viewId) {
        if (viewId == null) return false;
        String s = viewId.toString().toLowerCase(Locale.US);
        if (s.isEmpty() || !s.contains("close")) return false;
        // 排除「关闭页面/退出」类的非广告 id：要求带 ad 或 splash/open 广告语境
        if (s.contains("ad") || s.contains("splash") || s.contains("native")) return true;
        // 穿山甲(tt_)前缀的关闭钮：tt_ad_close / tt_splash_close 等红果系真机形态
        return s.startsWith("tt_") && (s.contains("skip") || s.contains("count"));
    }

    /** 关闭/跳过钮 id 的宽匹配：close 之外，跳过钮本体也按 id 命中（抖音 splash 常无 close 字样）。 */
    public static boolean isAdSkipId(CharSequence viewId) {
        if (viewId == null) return false;
        String s = viewId.toString().toLowerCase(Locale.US);
        if (s.isEmpty() || s.length() > 64) return false;
        // 必须 ad/splash/tt_ 语境 + skip 组合，避免误伤「跳到主页」类业务控件
        boolean adCtx = s.contains("ad") || s.contains("splash") || s.startsWith("tt_");
        return adCtx && (s.contains("skip") || s.contains("jump_over") || s.contains("count_down"));
    }

    public static boolean isSkipText(CharSequence text) {
        if (text == null) return false;
        String s = text.toString().trim();
        if (s.isEmpty() || s.length() > 8) return false;
        if (s.contains("不跳过") || s.contains("无法跳过")) return false;
        return s.contains("跳过") || s.toLowerCase(Locale.US).contains("skip");
    }
}
