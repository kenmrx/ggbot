package com.ggbot.app;

/**
 * 调色板与对比度计算。
 *
 * 单独拎出来只为一件事：让「这个灰是不是太暗」变成能断言的数字，而不是靠肉眼说「好像有点灰」。
 * 本类不 import 任何 android.*，所以纯 JVM 测试能直接调。
 *
 * 2026-10-30 走查结论：原来的 DIM(#5C6B63) 对 #0B0F0D 背景只有 3.43:1，
 * 低于 WCAG AA 的 4.5:1；而它正是次要文案（时间、依据、说明）用的颜色。
 * 现在提到 #73847C = 4.89:1，仍比 MUTED(5.8:1) 暗一档，层级没丢。
 */
public final class Tone {

    public static final int BG = 0xFF0B0F0D;
    public static final int CARD = 0xFF141C18;
    public static final int CARD2 = 0xFF1A241F;
    public static final int BORDER = 0xFF22302A;
    public static final int LINE = 0xFF1E2A24;
    public static final int FG = 0xFFE9F2ED;
    public static final int MUTED = 0xFF7F9189;
    public static final int DIM = 0xFF73847C;
    public static final int ACCENT = 0xFF35D07F;
    public static final int ACCENT_DEEP = 0xFF22B96B;
    public static final int ACCENT_SOFT = 0x2435D07F;
    public static final int DARK = 0xFF06231A;
    public static final int RIPPLE = 0x26FFFFFF;

    /** 最小可点高度（dp）：Material 与 WCAG 2.2 一致要求。 */
    public static final int TOUCH_DP = 48;

    /** 正文（FG）与次要文案（MUTED/DIM）对背景的最低对比度要求。 */
    public static final double AA_TEXT = 4.5;

    public static double contrast(int fg, int bg) {
        double a = relLum(fg);
        double b = relLum(bg);
        double hi = Math.max(a, b);
        double lo = Math.min(a, b);
        return (hi + 0.05) / (lo + 0.05);
    }

    private static double relLum(int c) {
        return 0.2126 * ch((c >> 16) & 0xFF)
                + 0.7152 * ch((c >> 8) & 0xFF)
                + 0.0722 * ch(c & 0xFF);
    }

    private static double ch(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }
}
