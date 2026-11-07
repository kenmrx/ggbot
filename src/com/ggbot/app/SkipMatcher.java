package com.ggbot.app;

import java.util.Locale;

/**
 * 开屏「跳过」按钮的判定（纯逻辑，桌面可测）。
 *
 * 只认「短文本里带跳过/Skip」：开屏按钮通常是「跳过」「跳过广告 3」「Skip 3s」，
 * 而正文里提到跳过的句子都很长。上限取 10 个字符，宁可漏点也不乱点。
 */
public final class SkipMatcher {

    private SkipMatcher() {
    }

    public static boolean isSkipText(CharSequence text) {
        if (text == null) return false;
        String s = text.toString().trim();
        if (s.isEmpty() || s.length() > 10) return false;
        if (s.contains("不跳过")) return false;
        return s.contains("跳过") || s.toLowerCase(Locale.US).contains("skip");
    }
}
