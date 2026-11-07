package com.ggbot.app;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * 日志折叠：同一个域名反复出现时只留一行，右侧标出次数。
 *
 * 思路来自算法助手Pro 2.0.4 的「重复日志折叠」（res/values/strings.xml 的
 * title_foldDuplicateLogs；实现见 plugin/SqlDuplicateLogFolder.java:583，按内容摘要分组、
 * 取每组最新一条、_id 倒序分页）。它用 SQL 分组，我们日志在内存里、量也小，
 * 用一次遍历做同样的事：键是「结果 + 域名」，保留最近一条的时间，计数累加。
 *
 * 折叠只改「怎么显示」，不改记录本身：点开还是能看到这一组的最近一条。
 */
public final class Fold {

    /** 筛选用的「不过滤」。 */
    public static final int ANY = -1;

    /** 一组相同目标的记录。 */
    public static final class Group {
        public final String host;
        public final int outcome;
        public final long latest;
        public int count;

        Group(String host, int outcome, long latest) {
            this.host = host;
            this.outcome = outcome;
            this.latest = latest;
            this.count = 1;
        }

        public String timeText() {
            return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(latest));
        }

        public String label() {
            return outcome == QueryLog.BLOCK ? "拦截"
                    : outcome == QueryLog.DECRYPT ? "解密" : "放行";
        }

        /** 「×3」这种后缀，只有一次就不显示，免得每行都拖着尾巴。 */
        public String countText() {
            return count > 1 ? " ×" + count : "";
        }
    }

    private Fold() {
    }

    /**
     * 按「结果 + 域名」合并；输入是新→旧，输出保持首次出现的顺序，
     * 也就是同一组里最新的那条在最上面。
     */
    public static Group[] byHost(QueryLog.Entry[] in) {
        if (in == null || in.length == 0) return new Group[0];
        LinkedHashMap<String, Group> map = new LinkedHashMap<>();
        for (QueryLog.Entry e : in) {
            String key = e.outcome + "|" + e.host;
            Group g = map.get(key);
            if (g == null) {
                map.put(key, new Group(e.host, e.outcome, e.time));
            } else {
                g.count++;
            }
        }
        return map.values().toArray(new Group[0]);
    }

    /** 只看某一类结果；ANY 原样返回。 */
    public static QueryLog.Entry[] filter(QueryLog.Entry[] in, int outcome) {
        if (in == null) return new QueryLog.Entry[0];
        if (outcome == ANY) return in;
        List<QueryLog.Entry> out = new ArrayList<>(in.length);
        for (QueryLog.Entry e : in) {
            if (e.outcome == outcome) out.add(e);
        }
        return out.toArray(new QueryLog.Entry[0]);
    }
}
