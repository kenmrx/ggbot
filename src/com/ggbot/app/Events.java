package com.ggbot.app;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 结构化请求记录：每条都带域名、路径、处理结果和命中依据。
 *
 * 与 Diag 的区别：Diag 是给人看的流水文本；Events 是给界面点击用的数据，
 * 每条都能顺着它去加白名单或补规则。只在内存里，退出即清空。
 */
public final class Events {

    private static final int MAX = 300;
    private static final ArrayDeque<Event> LOG = new ArrayDeque<>();

    private Events() {
    }

    public static final class Event {
        public final long time;
        public final String host;
        public final String path;
        public final boolean blocked;
        public final String rule;

        Event(long time, String host, String path, boolean blocked, String rule) {
            this.time = time;
            this.host = host;
            this.path = path;
            this.blocked = blocked;
            this.rule = rule;
        }

        public String timeText() {
            return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(time));
        }

        public String url() {
            return host + (path == null || path.isEmpty() ? "/" : path);
        }

        public String line() {
            return timeText() + "  " + (blocked ? "拦截" : "放行") + "  " + url();
        }
    }

    public static void add(String host, String path, boolean blocked, String rule) {
        if (host == null || host.isEmpty()) return;
        synchronized (LOG) {
            LOG.addFirst(new Event(System.currentTimeMillis(), host, path, blocked, rule));
            while (LOG.size() > MAX) LOG.removeLast();
        }
    }

    public static Event[] recent() {
        synchronized (LOG) {
            return LOG.toArray(new Event[0]);
        }
    }

    public static int size() {
        synchronized (LOG) {
            return LOG.size();
        }
    }

    public static void clear() {
        synchronized (LOG) {
            LOG.clear();
        }
    }
}
