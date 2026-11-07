package com.ggbot.app;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 诊断导出：把内存里的诊断事件、查询日志与一行行状态拼成纯文本，交给 SAF 存文件。
 *
 * 做法参考 NetGuard 的 ActivityLog 导出（它导 pcap；我们只接管 DNS，导文本更有用）：
 * 真机出问题时用户点一下就能把现场发过来，不用再靠口头描述复现。
 * 拼装逻辑保持纯函数，桌面门闸可以直接断言。
 */
public final class Export {

    private Export() {
    }

    public static String build(long now, String version, String engine,
                               String[] events, String[][] queries, String[] notes) {
        StringBuilder b = new StringBuilder(4096);
        b.append("GGBot 诊断导出\n");
        b.append("时间：")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(now)))
                .append('\n');
        b.append("版本：").append(version).append("  引擎：").append(engine).append('\n');
        if (notes != null) {
            for (String n : notes) {
                if (n != null) b.append("· ").append(n).append('\n');
            }
        }
        b.append("\n[诊断事件]\n");
        if (events == null || events.length == 0) {
            b.append("(无)\n");
        } else {
            for (String e : events) b.append(e).append('\n');
        }
        b.append("\n[查询日志]\n");
        if (queries == null || queries.length == 0) {
            b.append("(无)\n");
        } else {
            for (String[] q : queries) {
                b.append(q[0]).append('\t').append(q[1]).append('\t').append(q[2]).append('\n');
            }
        }
        return b.toString();
    }

    /** 文件名带日期时分，多次导出不会互相覆盖。 */
    public static String fileName(long now) {
        return "ggbot-diag-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date(now)) + ".txt";
    }
}
