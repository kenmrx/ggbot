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
 *     （真机关闭钮的 contentDescription 通常仍含"跳过"，那条路能接住）
 */
public final class SkipMatcher {

    private SkipMatcher() {
    }

    public static boolean isSkipText(CharSequence text) {
        if (text == null) return false;
        String s = text.toString().trim();
        if (s.isEmpty() || s.length() > 8) return false;
        if (s.contains("不跳过") || s.contains("无法跳过")) return false;
        return s.contains("跳过") || s.toLowerCase(Locale.US).contains("skip");
    }
}
