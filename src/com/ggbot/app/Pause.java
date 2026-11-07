package com.ggbot.app;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 暂停过滤的纯逻辑。
 *
 * 做法参考 DNS66 的 AdVpnService.pauseVpn()：暂停时停掉引擎，另发一条「已暂停」通知，
 * 点它恢复；AdAway 则是通知里带暂停/恢复两个动作。
 * 两家都是「暂停到手动恢复」，我们补一个定时自动恢复——暂停本该是临时的，
 * 忘了点回来等于防护一直关着，而界面上不一定看得出来。
 */
public final class Pause {

    /** 通知栏暂停默认时长（分钟）。 */
    public static final int DEFAULT_MINUTES = 15;

    private Pause() {
    }

    /** 从现在起暂停 minutes 分钟，返回截止时刻；非法时长按默认值处理。 */
    public static long until(long now, int minutes) {
        int m = minutes <= 0 ? DEFAULT_MINUTES : minutes;
        return now + m * 60_000L;
    }

    /** 是否仍在暂停期内。 */
    public static boolean active(long until, long now) {
        return until > now;
    }

    /** 恢复时刻，HH:mm；没有暂停返回空串。 */
    public static String resumeLabel(long until) {
        if (until <= 0) return "";
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(until));
    }

    /** 一句话状态，给通知和设置页共用。 */
    public static String text(long now, long until) {
        return active(until, now) ? "暂停至 " + resumeLabel(until) : "未暂停";
    }
}
