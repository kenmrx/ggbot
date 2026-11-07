package com.ggbot.app;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** 解密事件的时间线，只存在内存里，供界面查看，不落盘。 */
public final class Diag {

    private static final int MAX = 60;
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();

    private Diag() {
    }

    public static void push(String text, String kind) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        synchronized (LOG) {
            LOG.addFirst(ts + "  " + text);
            while (LOG.size() > MAX) LOG.removeLast();
        }
        // 同时镜像到 logcat，方便命令行侧排查（内容与界面完全一致）。
        // 这里必须兜住：桌面测试用的 android.jar 是空桩，Log 会直接抛 Stub!，
        // 一条诊断日志失败不该把正在跑的拦截逻辑带崩。
        try {
            Log.i("GGBot", text);
        } catch (Throwable ignored) {
        }
    }

    public static String[] recent() {
        synchronized (LOG) {
            return LOG.toArray(new String[0]);
        }
    }

    public static void clear() {
        synchronized (LOG) {
            LOG.clear();
        }
    }
}
