package com.ggbot.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;

/** 开机自启：只在用户勾选了「开机自动启动」且之前是运行状态时才拉起。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        Config.init(context);
        // 规则更新是常驻任务，与「开机自启 VPN」无关：
        // 用户可能只想每天悄悄更新规则，不想一开机就被挂上代理。
        // 放在这里重排一次，也能兜住那些吞掉 persisted job 的 ROM。
        FeedJob.schedule(context);
        if (!Config.boot(context) || !Config.enabled(context)) return;
        // 没有 VPN 授权时 prepare 会返回 Intent，此时无法在后台弹出授权，直接放弃
        if (VpnService.prepare(context) != null) return;

        Intent svc = new Intent(context, DnsVpnService.class);
        svc.setAction(DnsVpnService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception ignored) {
        }
    }
}
