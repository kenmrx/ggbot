package com.ggbot.app;

/**
 * 通知权限的请求策略（纯逻辑，可单测）。
 *
 * 背景：原来 onCreate 里每次冷启动都 requestPermissions。用户一旦点了「不允许」，
 * 下一次启动还会再弹一次系统框，把首页顶掉（实测：焦点跑到 GrantPermissionsActivity），
 * 而用户并没有新做任何操作——这是典型的「反复打扰」。
 *
 * 规则改成：只在没用过通知权限、也没问过的情况下自动问一次；之后不再自动弹，
 * 交给设置里的入口或系统设置页。
 */
public final class Notif {

    /** 当前系统是否已经授予通知权限（外部传入，避免这里碰 Context）。 */
    public static boolean shouldAsk(int sdk, boolean granted, boolean askedBefore) {
        if (sdk < 33) return false;      // 33 以前没有运行时通知权限
        if (granted) return false;       // 已经有了，不问
        return !askedBefore;             // 只自动问一次
    }

    public static String text(boolean granted, boolean askedBefore) {
        if (granted) return "通知已开启";
        return askedBefore ? "通知被关闭（点此去开启）" : "通知未开启";
    }
}
