package com.ggbot.app;

import java.util.HashMap;

/**
 * 同一域名的并发查询合并（single-flight）。
 *
 * 手机上一个页面启动时，十几个进程可能在同一毫秒里问同一个域名：
 * 每个都发一次上游查询、都占一个线程、都等一个往返。Pi-hole 与 dnsmasq 都做了合并，
 * 只放第一个出去，其余在原地等它的结果。
 *
 * 这里刻意只让「等待」有上限：等不到就自己去查，绝不因为队长卡住而让客户端超时。
 * 等待者拿到的是同一份字节数组，必须自己 clone 再改事务 ID。
 */
public final class Coalescer {

    /** 等待者最多等这么久，超时就自己去查。 */
    public static final long WAIT_MS = 1200L;

    private static final class Slot {
        byte[] resp;
        boolean done;
    }

    private final HashMap<String, Slot> inflight = new HashMap<>(64);

    public static final class Ticket {
        public final boolean owner;
        private final Slot slot;

        Ticket(boolean owner, Slot slot) {
            this.owner = owner;
            this.slot = slot;
        }

        /** 等待上游结果；超时或已被清理返回 null，调用方应自行查询。 */
        public byte[] await(long ms) {
            long deadline = System.currentTimeMillis() + ms;
            synchronized (slot) {
                while (!slot.done) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) return null;
                    try {
                        slot.wait(left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
                return slot.resp;
            }
        }
    }

    /**
     * 报名。owner 为 true 的一方负责真正发查询，并且无论成败都必须调用 {@link #finish}，
     * 否则等待者会一直等到超时。
     */
    public Ticket begin(String key) {
        if (key == null) return new Ticket(true, new Slot());
        synchronized (inflight) {
            Slot s = inflight.get(key);
            if (s == null) {
                s = new Slot();
                inflight.put(key, s);
                return new Ticket(true, s);
            }
            return new Ticket(false, s);
        }
    }

    /** 公布结果并唤醒等待者。resp 为 null 表示查询失败。 */
    public void finish(String key, byte[] resp) {
        if (key == null) return;
        Slot s;
        synchronized (inflight) {
            s = inflight.remove(key);
        }
        if (s == null) return;
        synchronized (s) {
            s.resp = resp;
            s.done = true;
            s.notifyAll();
        }
    }

    /** 通道关闭时清空，避免残留的等待者永远挂着。 */
    public void clear() {
        HashMap<String, Slot> copy;
        synchronized (inflight) {
            copy = new HashMap<>(inflight);
            inflight.clear();
        }
        for (Slot s : copy.values()) {
            synchronized (s) {
                s.done = true;
                s.notifyAll();
            }
        }
    }

    public int size() {
        synchronized (inflight) {
            return inflight.size();
        }
    }
}
