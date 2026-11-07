package com.ggbot.app;

/**
 * 首页状态文案（纯逻辑，可单测）。
 *
 * 背景：断网时首页只写「防护已开启」，用户会以为拦截失效。实际是通道在、上游不可达。
 * 把「有没有网」和「通道开没开」拆成两句话说，避免误判，也避免把断网当成软件坏了。
 */
public final class Net {

    public static String status(boolean running, boolean online) {
        if (running) return online ? "防护已开启" : "防护已开启 · 当前无网络";
        return online ? "防护已关闭" : "防护已关闭 · 当前无网络";
    }

    /** 无网络时应该是灰色次要色，不要用强调色误导成「正常工作中的告警」。 */
    public static boolean accent(boolean running) {
        return running;
    }
}
