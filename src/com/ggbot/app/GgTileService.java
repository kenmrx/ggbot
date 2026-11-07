package com.ggbot.app;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 快速设置磁贴：通知栏一键启停防护。
 *
 * 参考应用里 6/9 有自家磁贴（AdAway、Blokada、GKD、NetGuard、
 * PersonalDNSFilter、RethinkDNS；DNS66 与两个 Xposed 模块没有），
 * 唯 GGBot 缺——每次开关都得进 App 点两下。
 * （2026-10-30 复核修正：上一版误把 DNS66 列入、漏了 Blokada 与 GKD。）
 */
public class GgTileService extends TileService {

    @Override
    public void onStartListening() {
        // 面板被下拉 = 系统把本进程拉起来了一次，顺手自检无障碍还在不在
        A11yWatch.probe(this);
        update();
    }

    @Override
    public void onClick() {
        if (DnsVpnService.isRunning()) {
            stopVpn();
        } else if (VpnService.prepare(this) == null) {
            startVpn();
        } else {
            // 没授权时磁贴里弹不出授权对话框，带用户去主界面完成一次授权
            openApp();
        }
        update();
    }

    @SuppressWarnings("deprecation")
    private void startAndCollapseCompat(Intent i) {
        startActivityAndCollapse(i);
    }

    private void openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(
                        this, 0, i, PendingIntent.FLAG_IMMUTABLE));
            } else {
                // Intent 版本 34 起废弃，但 24–33 是唯一途径；压告警集中在这里
                startAndCollapseCompat(i);
            }
        } catch (Exception ignored) {
            try {
                startActivity(i);
            } catch (Exception ignored2) {
            }
        }
    }

    private void startVpn() {
        Config.init(this);
        Intent i = new Intent(this, DnsVpnService.class);
        i.setAction(DnsVpnService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception ignored) {
        }
    }

    private void stopVpn() {
        Intent i = new Intent(this, DnsVpnService.class);
        i.setAction(DnsVpnService.ACTION_STOP);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception ignored) {
        }
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = DnsVpnService.isRunning();
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        try {
            t.setSubtitle(on ? "防护运行中" : "已停用");
        } catch (Exception ignored) {
            // 老系统上副标题不可用就算了，状态色本身已足够表达
        }
        t.updateTile();
    }
}
