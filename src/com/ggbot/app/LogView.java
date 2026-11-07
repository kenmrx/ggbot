package com.ggbot.app;

/**
 * 日志列表的渲染上限（纯逻辑，可单测）。
 *
 * 背景：QueryLog 环形缓存留 200 条，而日志页是往 LinearLayout 里一条条 addView，
 * 没有任何复用。行高抬到 48dp 后 200 行就是 200 个视图，全部在 UI 线程构建。
 * 满量时的实际耗时在 Diag 里记（轮3 实测），这里只负责「超过上限就别全渲染」，
 * 并在页尾把话说清楚，不让人以为记录丢了。
 */
public final class LogView {

    /** 一次最多渲染多少行；超出部分折叠成一行说明。 */
    public static final int CAP = 120;

    public static int visible(int total, int cap) {
        if (total <= 0) return 0;
        return Math.min(total, Math.max(0, cap));
    }

    public static boolean truncated(int total, int shown) {
        return total > shown;
    }

    public static String footer(int total, int shown) {
        if (!truncated(total, shown)) return "";
        return "只显示最近 " + shown + " 条，更早的 " + (total - shown) + " 条未渲染（保留在内存，切换筛选或折叠不影响计数）";
    }
}
