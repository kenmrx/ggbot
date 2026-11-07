package com.ggbot.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 拦截计数，按天归零另累计总数。
 *
 * 写模型照 TrafficStats 的自家纪律：DNS 查询是包路径热区，早先每个报文一次
 * SharedPreferences.apply()，几十 QPS 就是每秒几十个落盘任务排进 QueuedWork——
 * Activity onStop 要等队列排空，慢闪存上是典型 ANR 形态。所以「今日」计数只在
 * 内存加，flush() 才落盘；落盘点挂在已有的低频节流上（通知刷新 2.5s 节流、
 * 服务收尾）。「累计」是低频大数，直接持久层读改写没有热区问题。
 * 跨天结算的键与旧版完全一致（day/today/todayQ/total/totalQ），升级无感。
 */
public final class Stats {

    private static final String FILE = "ggbot_stats";
    private static final Object LOCK = new Object();

    /** 内存里的「今日」计数：热路径只写这里，flush 时才落到 SharedPreferences。 */
    private static int memToday;
    private static int memTodayQ;
    /** 内存计数归属的日期（跨天时把旧账并入累计、开新账）。 */
    private static String memDay = "";
    /** 进程启动后还没从持久层装载过。 */
    private static boolean loaded = false;

    private Stats() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    /** 首次访问装载 + 跨天落旧账。调用方必须持有 LOCK。 */
    private static void roll(SharedPreferences p) {
        String d = today();
        if (!loaded) {
            loaded = true;
            memDay = d;
            memToday = p.getInt("today", 0);
            memTodayQ = p.getInt("todayQ", 0);
        }
        if (d.equals(memDay)) return;
        long total = p.getLong("total", 0) + memToday;
        long totalQ = p.getLong("totalQ", 0) + memTodayQ;
        memToday = 0;
        memTodayQ = 0;
        memDay = d;
        p.edit().putString("day", d)
                .putInt("today", 0)
                .putInt("todayQ", 0)
                .putLong("total", total)
                .putLong("totalQ", totalQ)
                .apply();
    }

    /** 把内存计数落盘。挂在低频节流上（通知刷新/服务收尾），包路径热区不要调。 */
    public static void flush(Context c) {
        if (c == null) return;
        synchronized (LOCK) {
            if (!loaded) return;
            SharedPreferences p = sp(c);
            roll(p);
            p.edit().putInt("today", memToday).putInt("todayQ", memTodayQ).apply();
        }
    }

    public static void addBlock(Context c) {
        if (c == null) return;
        synchronized (LOCK) {
            roll(sp(c));
            memToday++;
        }
    }

    public static void addQuery(Context c) {
        if (c == null) return;
        synchronized (LOCK) {
            roll(sp(c));
            memTodayQ++;
        }
    }

    public static int todayQueries(Context c) {
        if (c == null) return 0;
        synchronized (LOCK) {
            roll(sp(c));
            return memTodayQ;
        }
    }

    public static long totalQueries(Context c) {
        if (c == null) return 0;
        synchronized (LOCK) {
            SharedPreferences p = sp(c);
            roll(p);
            return p.getLong("totalQ", 0) + memTodayQ;
        }
    }

    public static int todayCount(Context c) {
        if (c == null) return 0;
        synchronized (LOCK) {
            roll(sp(c));
            return memToday;
        }
    }

    public static long totalCount(Context c) {
        if (c == null) return 0;
        synchronized (LOCK) {
            SharedPreferences p = sp(c);
            roll(p);
            return p.getLong("total", 0) + memToday;
        }
    }

    public static void reset(Context c) {
        if (c == null) return;
        synchronized (LOCK) {
            loaded = true;
            memDay = today();
            memToday = 0;
            memTodayQ = 0;
            sp(c).edit().putString("day", memDay)
                    .putInt("today", 0)
                    .putInt("todayQ", 0)
                    .putLong("total", 0)
                    .putLong("totalQ", 0)
                    .apply();
        }
    }
}
