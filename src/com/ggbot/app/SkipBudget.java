package com.ggbot.app;

/**
 * 单页跳过预算：一次开屏最多点几下、两次点之间至少隔多久、进页多久后就不再碰屏幕。
 *
 * 思路取自 GKD 的规则状态机（matchTime 时间窗 + actionCd 冷却 + actionMaximum 次数上限，
 * 见拆解报告 01-无障碍规则引擎 §4「规则状态机」）。GGBot 没有规则 DSL，但同样的毛病会有：
 * 某些开屏的「跳过」是个假按钮（点了没反应），或者一屏好几个「跳过」字样，
 * 没有上限就会反复点——耗电、误触、还可能把正常页面的按钮点掉。
 *
 * 纯函数，不碰系统 API，门闸里直接断言。
 */
public final class SkipBudget {

    /** 进页后的有效窗口：超时说明这页不是开屏，收手。 */
    public static final long WINDOW_MS = 10_000L;
    /** 两次点击的最小间隔。 */
    public static final long COOLDOWN_MS = 1_500L;
    /** 同一页最多点几次。 */
    public static final int MAX_CLICKS = 5;

    public static final String OK = "ok";
    public static final String COOLING = "cooling";
    public static final String USED_UP = "used_up";
    public static final String EXPIRED = "expired";

    private SkipBudget() {
    }

    /**
     * 判定顺序照 GKD 的状态机：先看次数（已经点够就别再点），再看冷却，最后看时间窗。
     * enterAt / lastClickAt 为 0 表示「还没有」——首次进页时不惩罚。
     */
    public static String decide(long now, long enterAt, long lastClickAt, int clicks) {
        if (clicks >= MAX_CLICKS) return USED_UP;
        if (lastClickAt > 0 && now - lastClickAt < COOLDOWN_MS) return COOLING;
        if (enterAt > 0 && now - enterAt > WINDOW_MS) return EXPIRED;
        return OK;
    }

    /** 一句话说明，写进 Diag 或界面提示。 */
    public static String text(String state, int clicks) {
        if (USED_UP.equals(state)) {
            return "本页已点 " + clicks + " 次（上限 " + MAX_CLICKS + "），不再自动点";
        }
        if (COOLING.equals(state)) return "冷却中（间隔 " + (COOLDOWN_MS / 1000.0) + " 秒）";
        if (EXPIRED.equals(state)) return "已过 " + (WINDOW_MS / 1000) + " 秒窗口，本页不再自动点";
        return "可点";
    }
}
