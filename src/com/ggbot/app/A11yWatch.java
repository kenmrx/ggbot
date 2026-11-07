package com.ggbot.app;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

/**
 * 监听系统里的无障碍服务名单，被摘掉/恢复时立刻知道。
 *
 * 吸收自 GKD 1.12.1 的思路（读其 dex 与反编译输出确认）：它对
 * Settings.Secure.enabled_accessibility_services 注了一个 ContentObserver，
 * 名单一变就当帧处理；我们原来是守卫定时巡检（最长 10 秒盲区），
 * 用户手动拨开关后界面可能还显示旧状态。
 *
 * 只吸收做法，不引入对方任何代码。
 */
public final class A11yWatch {

    /** 系统里存“已启用无障碍服务列表”的那个 Secure 键，写错就监听不到任何变化。 */
    public static final String KEY = "enabled_accessibility_services";

    private static ContentObserver observer;

    private A11yWatch() {
    }

    /** 状态变了该说什么；没变返回 null（调用方据此决定要不要打扰用户）。 */
    public static String describe(boolean prev, boolean now) {
        if (prev == now) return null;
        return now ? "无障碍已恢复" : "无障碍已断开";
    }

    /** 磁贴文案：下拉面板时就该看到无障碍是真还是断。 */
    public static String tileHint(boolean now) {
        return now ? "开屏跳过：可用" : "开屏跳过：已断开";
    }

    /** 磁贴告警的节流时间戳（静态即可，面板下拉频率远低于 10 分钟一次）。 */
    private static volatile long lastTileWarnAt = 0L;

    /**
     * 下拉面板 / 点开关时的自检。
     *
     * 吸收自 GKD 的「无感保活」：它把快捷开关本身当成重生入口——只要开关在面板里，
     * 无论被系统杀后台还是自己崩了，下拉一下面板，SystemUI 就会把我们的进程拉起来。
     * 我们在这里补一次状态自检，断开发通知把用户领回去，不断则静默。
     */
    public static void probe(Context c) {
        Context ctx = c.getApplicationContext();
        boolean on = A11y.enabledNow(ctx);
        boolean prev = Config.a11yLastKnown(ctx);
        String msg = describe(prev, on);
        Config.setA11yLastKnown(ctx, on);
        long now = System.currentTimeMillis();
        if (msg != null) {
            Diag.push("磁贴自检: " + msg, on ? "info" : "warn");
        } else if (!on && now - lastTileWarnAt > 10 * 60_000L) {
            // 没有状态变化但已经断了：面板每次下拉都能看到，十分钟最多记一次
            lastTileWarnAt = now;
            Diag.push("磁贴自检: 无障碍未开启，开屏跳过不可用", "warn");
        }
        if (!on) A11y.notifyMissing(ctx);
    }

    public static synchronized void register(Context c) {
        if (observer != null) return;
        try {
            observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean self) {
                    Context ctx = c.getApplicationContext();
                    boolean now = A11y.enabledNow(ctx);
                    boolean prev = Config.a11yLastKnown(ctx);
                    String msg = describe(prev, now);
                    Config.setA11yLastKnown(ctx, now);
                    if (msg != null) Diag.push(msg, now ? "info" : "warn");
                    if (!now) A11y.notifyMissing(ctx);
                }
            };
            c.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(KEY), false, observer);
            Diag.push("无障碍状态监听已挂上", "info");
        } catch (Exception e) {
            Diag.push("无障碍状态监听挂载失败 " + e.getClass().getSimpleName(), "warn");
        }
    }

    public static synchronized void unregister(Context c) {
        if (observer == null) return;
        try {
            c.getContentResolver().unregisterContentObserver(observer);
        } catch (Exception ignored) {
        }
        observer = null;
    }
}
