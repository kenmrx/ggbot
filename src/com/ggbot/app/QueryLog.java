package com.ggbot.app;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * DNS 层的最近查询记录（含放行）。
 *
 * 拦截记录只反映「拦了什么」；开屏广告排查要的是反过来的问题——
 * 「这个 App 刚才到底查了哪些没被拦的域名」。所以放行也要记，
 * 点开一条放行记录可以直接把它加进拦截或解密名单。
 * 只在内存里，退出即清空，不落盘。
 */
public final class QueryLog {

    public static final int ALLOW = 0;
    public static final int BLOCK = 1;
    public static final int DECRYPT = 2;

    private static final int MAX = 200;
    private static final ArrayDeque<Entry> LOG = new ArrayDeque<>();

    private QueryLog() {
    }

    public static final class Entry {
        public final long time;
        public final String host;
        public final int outcome;

        Entry(long time, String host, int outcome) {
            this.time = time;
            this.host = host;
            this.outcome = outcome;
        }

        public String timeText() {
            return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(time));
        }

        public String label() {
            return outcome == BLOCK ? "已拦截" : outcome == DECRYPT ? "解密名单" : "放行";
        }
    }

    public static void add(String host, int outcome) {
        if (host == null || host.isEmpty()) return;
        synchronized (LOG) {
            LOG.addFirst(new Entry(System.currentTimeMillis(), host, outcome));
            while (LOG.size() > MAX) LOG.removeLast();
        }
    }

    public static Entry[] recent() {
        synchronized (LOG) {
            return LOG.toArray(new Entry[0]);
        }
    }

    public static void clear() {
        synchronized (LOG) {
            LOG.clear();
        }
    }
}
