package com.ggbot.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * VPN 流量计量：只统计进入隧道的字节（DNS 查询与解密域名的转发），
 * 不碰物理网卡上的其余流量。
 *
 * 热路径只在内存里加 AtomicLong；落盘由服务低频触发（通知节流 2.5s 一次
 * + 停服一次），跨日把昨日并入总计。服务停止后统计保留。
 */
public final class TrafficStats {

    private static final String FILE = "ggbot_traffic";
    private static final Object LOCK = new Object();
    private static final AtomicLong TX = new AtomicLong();
    private static final AtomicLong RX = new AtomicLong();

    private TrafficStats() {
    }

    /** 从隧道读到的字节：设备对外发送。 */
    public static void addTx(long n) {
        if (n > 0) TX.addAndGet(n);
    }

    /** 写回隧道的字节：设备接收。 */
    public static void addRx(long n) {
        if (n > 0) RX.addAndGet(n);
    }

    /** 会话累计并进持久层（今日 + 总计），跨日自动归零。 */
    public static void flush(Context c) {
        if (c == null) return;
        long tx = TX.getAndSet(0);
        long rx = RX.getAndSet(0);
        if (tx == 0 && rx == 0) return;
        synchronized (LOCK) {
            SharedPreferences p = c.getApplicationContext()
                    .getSharedPreferences(FILE, Context.MODE_PRIVATE);
            String d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            if (!d.equals(p.getString("day", ""))) {
                p.edit().putString("day", d)
                        .putLong("txTotal", p.getLong("txTotal", 0) + p.getLong("tx", 0))
                        .putLong("rxTotal", p.getLong("rxTotal", 0) + p.getLong("rx", 0))
                        .putLong("tx", 0)
                        .putLong("rx", 0)
                        .apply();
            }
            p.edit().putLong("tx", p.getLong("tx", 0) + tx)
                    .putLong("rx", p.getLong("rx", 0) + rx)
                    .putLong("txTotal", p.getLong("txTotal", 0) + tx)
                    .putLong("rxTotal", p.getLong("rxTotal", 0) + rx)
                    .apply();
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static long todayTx(Context c) {
        return sp(c).getLong("tx", 0) + TX.get();
    }

    public static long todayRx(Context c) {
        return sp(c).getLong("rx", 0) + RX.get();
    }

    public static long totalTx(Context c) {
        return sp(c).getLong("txTotal", 0) + TX.get();
    }

    public static long totalRx(Context c) {
        return sp(c).getLong("rxTotal", 0) + RX.get();
    }
}
