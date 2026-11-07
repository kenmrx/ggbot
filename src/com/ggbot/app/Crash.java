package com.ggbot.app;

/**
 * 崩溃描述的纯逻辑。
 *
 * 起因：模拟器上排查时发现应用被系统/厂商杀掉后，界面上只看到「防护已关闭」，
 * 而 Diag 里没有任何痕迹，只能靠 logcat。这里把未捕获异常写进 Diag（内存+logcat），
 * 至少让用户能在应用内看到「它自己崩过」而不是「什么都没发生」。
 *
 * 注意：不做“吞掉异常”的处理——仍然交给系统默认 handler，保持崩溃可观测。
 */
public final class Crash {

    public static String describe(Throwable t) {
        if (t == null) return "崩溃: 未知";
        String msg = t.getMessage();
        if (msg == null || msg.trim().isEmpty()) msg = "(无消息)";
        String where = "?";
        StackTraceElement[] st = t.getStackTrace();
        if (st != null && st.length > 0) {
            where = st[0].getClassName() + "." + st[0].getMethodName()
                    + ":" + st[0].getLineNumber();
        }
        return "崩溃: " + t.getClass().getSimpleName() + " @ " + where + " — " + msg;
    }
}
