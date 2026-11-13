package com.ggbot.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GGBot 拦截引擎。
 *
 * 只建立一条承载 DNS 的最小 VPN 通道：本机地址 198.18.0.2/32，系统 DNS 指向 198.18.0.1。
 * 路由只放行 198.18.0.0/16 这段 RFC 2544 保留地址，公网不可达、家用路由器也不会分配。也就是说只有 DNS 查询会进隧道，其余流量原样走物理网卡，
 * 不改变出口 IP、不降低带宽、不接管明文流量。
 *
 * 处理流程：解 IPv4/UDP → 取 QNAME/QTYPE → 白名单放行 → 黑名单拦截 → 缓存命中 → 上游转发。
 * 全程在内存里完成，不写日志文件、不上报任何服务器。
 */
public class DnsVpnService extends VpnService implements TcpProxy.Sink {

    public static final String ACTION_START = "com.ggbot.app.action.START";
    public static final String ACTION_STOP = "com.ggbot.app.action.STOP";
    /** 暂停过滤：停引擎但留一条可点恢复的通知，到点自动恢复。 */
    public static final String ACTION_PAUSE = "com.ggbot.app.action.PAUSE";
    public static final String ACTION_RESUME = "com.ggbot.app.action.RESUME";
    public static final String EXTRA_MINUTES = "minutes";

    private static final String CHANNEL_ID = "ggbot_status";
    private static final int NOTIF_ID = 1001;
    /** 暂停态通知走单独通道，避免被「正在过滤」那条的前台身份覆盖。 */
    private static final String CH_PAUSED = "ggbot_paused";
    private static final int PAUSED_ID = 1002;

    private static final String TUN_IP = "198.18.0.2";

    /** UDP 应答能安全写回隧道的最大长度：MTU 1500 − IP 20 − UDP 8。 */
    private static final int MAX_UDP_RESP = 1472;
    /** 合并查询的判定标记：owner 判定「要拦」时通过 Coalescer 发给等待者。 */
    private static final byte[] BLOCKED_MARK = new byte[0];

    private static final AtomicLong QUERIES = new AtomicLong();
    private static final AtomicLong BLOCKED = new AtomicLong();
    /** 因 CNAME 链命中而拦下的查询数，单独计数便于判断这条规则到底有没有用。 */
    private static final AtomicLong CNAME = new AtomicLong();
    /** 被 ICMP 端口不可达挡回去的 QUIC/HTTP3 报文数。 */
    private static final AtomicLong QUIC_DENIED = new AtomicLong();
    /** 为保护解密链路而压掉的 AAAA 查询数。 */
    private static final AtomicLong IPV6_SUPPRESSED = new AtomicLong();
    private static final ArrayDeque<DnsBlock> RECENT = new ArrayDeque<>();
    private static volatile boolean running = false;

    private volatile ParcelFileDescriptor tunFd;
    /** 息屏保活（做法参考 NetGuard / PersonalDNSFilter）：CPU 睡着时 DNS 应答整段变慢。 */
    private PowerManager.WakeLock wl;
    /** tun 自愈守卫线程。 */
    private Thread guard;
    private volatile FileInputStream tunIn;
    private volatile FileOutputStream tunOut;
    private final Object writeLock = new Object();
    private Thread reader;
    private ConnectivityManager cmRef;
    private ConnectivityManager.NetworkCallback netCb;
    private volatile long lastNetAt = 0L;
    /** volatile：Reader 线程提交查询用、main 线程 startVpn/shutdown 写，换代必须可见。 */
    private volatile ExecutorService pool;
    private volatile boolean stopRequested;
    private final DnsCache cache = new DnsCache();
    private final Coalescer coalescer = new Coalescer();
    private long lastNotifyAt;
    private TcpProxy tcp;
    private Set<String> decryptSet = java.util.Collections.emptySet();
    private long decryptAt;

    public static boolean isRunning() {
        return running;
    }

    public static long queries() {
        return QUERIES.get();
    }

    public static long blocked() {
        return BLOCKED.get();
    }

    public static long cnameBlocked() {
        return CNAME.get();
    }

    public static long quicDenied() {
        return QUIC_DENIED.get();
    }

    public static long ipv6Suppressed() {
        return IPV6_SUPPRESSED.get();
    }

    public static DnsBlock[] recent() {
        synchronized (RECENT) {
            return RECENT.toArray(new DnsBlock[0]);
        }
    }

    public static void resetStats() {
        QUERIES.set(0);
        BLOCKED.set(0);
        CNAME.set(0);
        QUIC_DENIED.set(0);
        IPV6_SUPPRESSED.set(0);
        synchronized (RECENT) {
            RECENT.clear();
        }
    }

    /**
     * 记一条拦截。带上依据和 CNAME 跳转目标，界面上才能直接给出「放行」这个动作。
     * 只存字符串的话，用户看到域名还得自己抄到白名单里去。
     */
    private static void pushBlock(DnsBlock b) {
        synchronized (RECENT) {
            RECENT.addFirst(b);
            while (RECENT.size() > 60) RECENT.removeLast();
        }
    }

    /* ---------------- 生命周期 ---------------- */

    /**
     * 配置变了（省电模式/解密开关）之后重建通道。
     *
     * 不放 Activity 里做：设置页点完就 recreate()，Activity 上的延时任务会跟着一起废掉，
     * 实测就是“模式切了、隧道没了”。这里用 application context 重启，跟界面生命周期解耦。
     */
    public static void restartSoon(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (isRunning()) {
            Intent stop = new Intent(app, DnsVpnService.class);
            stop.setAction(ACTION_STOP);
            try {
                app.startService(stop);
            } catch (Exception ignored) {
            }
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (VpnService.prepare(app) != null) {
                    Diag.push("重建通道需要 VPN 授权，回主界面确认一次", "warn");
                    return;
                }
                Intent start = new Intent(app, DnsVpnService.class);
                start.setAction(ACTION_START);
                app.startService(start);
            } catch (Exception e) {
                Diag.push("重建通道失败: " + e, "error");
            }
        }, 900);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 名单一变就反应（吸收自 GKD 的 ContentObserver 做法），不用等下一次巡检
        Config.setA11yLastKnown(this, A11y.enabledNow(this));
        A11yWatch.register(this);
        if (intent != null && ACTION_PAUSE.equals(intent.getAction())) {
            // 经 startForegroundService 投递的暂停意图也要先过 startForeground
            startForegroundCompat();
            int mins = intent.getIntExtra(EXTRA_MINUTES, Pause.DEFAULT_MINUTES);
            long until = Pause.until(System.currentTimeMillis(), mins);
            Config.setPausedUntil(this, until);
            Config.setEnabled(this, false);
            shutdown();
            showPaused(until);
            scheduleResume(until);
            Diag.push("已暂停过滤，" + Pause.resumeLabel(until) + " 自动恢复", "warn");
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // 经 startForegroundService 投递的停止意图也必须先过 startForeground，
            // 否则系统按 ForegroundServiceDidNotStartInTime 强杀进程（实测复现）
            startForegroundCompat();
            Config.setEnabled(this, false);
            shutdown();
            return START_NOT_STICKY;
        }
        Config.init(this);
        prepareCa();
        // 任何一次正常启动都解除暂停态：手动点开、通知恢复、定时恢复都走这里
        if (Config.pausedUntil(this) > 0) {
            Config.setPausedUntil(this, 0L);
            cancelPausedNote();
        }
        startForegroundCompat();
        startVpn();
        return START_STICKY;
    }

    /**
     * CA 与叶子密钥对持久化在应用私有目录，保证多次启动用的是同一张 CA，
     * 用户装一次证书就长期有效。首次生成需要约两三百毫秒。
     */
    private void prepareCa() {
        ensureCa(this);
    }

    /**
     * 证书页在服务未运行时也要能拿到同一张 CA（用户装进系统的是哪张就得一直用哪张）：
     * 先读持久化材料，读不到才生成并保存。与 prepareCa 共用这一份逻辑。
     */
    public static void ensureCa(android.content.Context c) {
        if (CaStore.ready()) return;
        File dir = new File(c.getFilesDir(), "ca");
        File fk = new File(dir, "ca.pk8");
        File fc = new File(dir, "ca.der");
        File fl = new File(dir, "leaf.pk8");
        File fp = new File(dir, "leaf.pub");
        try {
            if (fk.exists() && fc.exists() && fl.exists() && fp.exists()) {
                CaStore.importKeys(readAll(fk), readAll(fc), readAll(fl), readAll(fp));
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            CaStore.init();
            dir.mkdirs();
            byte[][] k = CaStore.exportKeys();
            writeAll(fk, k[0]);
            writeAll(fc, k[1]);
            writeAll(fl, k[2]);
            writeAll(fp, k[3]);
        } catch (Exception ignored) {
        }
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
            return b;
        } finally {
            in.close();
        }
    }

    private static void writeAll(File f, byte[] data) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    /** minSdk 24 但通知渠道要求 26+：26 以下没有渠道概念，旧的构造器是唯一选择，收敛到这里消掉调用点的告警。 */
    @SuppressWarnings("deprecation")
    static Notification.Builder notificationBuilder(Context c, String channelId) {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(c, channelId);
        }
        return new Notification.Builder(c);
    }

    private void startForegroundCompat() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "防护状态", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent pause = new Intent(this, DnsVpnService.class);
        pause.setAction(ACTION_PAUSE);
        PendingIntent pausePi = PendingIntent.getForegroundService(
                this, 4, pause, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stop = new Intent(this, DnsVpnService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        long q = QUERIES.get();
        long b = BLOCKED.get();
        Notification.Builder bd = notificationBuilder(this, CHANNEL_ID);
        bd.setContentTitle("GGBot 正在过滤")
                .setContentText("已查询 " + q + " 次，拦截 " + b + " 次")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        // addAction(icon=0,…) 31 起废弃，但图标资源本就没用、24–30 无替代写法；集中压告警
        addActionCompat(bd, "暂停 15 分钟", pausePi);
        addActionCompat(bd, "停止", stopPi);
        return bd.build();
    }

    @SuppressWarnings("deprecation")
    private static void addActionCompat(Notification.Builder bd, String title, PendingIntent pi) {
        bd.addAction(0, title, pi);
    }

    /** 暂停态通知：非前台、可点恢复，到点由闹钟拉起来。 */
    private void showPaused(long until) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH_PAUSED) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CH_PAUSED, "暂停状态", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Intent res = new Intent(this, DnsVpnService.class);
            res.setAction(ACTION_RESUME);
            PendingIntent pi = PendingIntent.getForegroundService(
                    this, 5, res, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent open = PendingIntent.getActivity(
                    this, 0, new Intent(this, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder bd = notificationBuilder(this, CH_PAUSED);
            bd.setContentTitle("GGBot 已暂停")
                    .setContentText("过滤已暂停，" + Pause.resumeLabel(until) + " 自动恢复")
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentIntent(open)
                    .setOngoing(false)
                    .setAutoCancel(true);
            addActionCompat(bd, "立即恢复", pi);
            nm.notify(PAUSED_ID, bd.build());
        } catch (Exception ignored) {
        }
    }

    /** 到点把服务拉起来：闹钟用非精确唤醒即可，暂停恢复差几分钟无所谓。 */
    private void scheduleResume(long until) {
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am == null) return;
            Intent res = new Intent(this, DnsVpnService.class);
            res.setAction(ACTION_RESUME);
            PendingIntent pi = PendingIntent.getForegroundService(
                    this, 6, res, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, pi);
        } catch (Exception ignored) {
        }
    }

    /** 清掉暂停态通知与闹钟。 */
    private void cancelPausedNote() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(PAUSED_ID);
        } catch (Exception ignored) {
        }
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null) {
                Intent res = new Intent(this, DnsVpnService.class);
                res.setAction(ACTION_RESUME);
                PendingIntent pi = PendingIntent.getForegroundService(
                        this, 6, res, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                am.cancel(pi);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 网络切换回调（做法参考 AdAway 的 registerNetworkCallback 与 RethinkDNS 的 ConnectionMonitor）：
     * 换网后清 DNS 缓存与上游连接，否则缓存与 socket 可能挂在已经不通的网络上。
     */
    private void netChanged() {
        long now = System.currentTimeMillis();
        if (!NetSwitch.shouldAct(now, lastNetAt)) return;
        lastNetAt = now;
        try {
            cache.clear();
            RealResolver.clear();
        } catch (Exception ignored) {
        }
        Diag.push("网络已切换：清空 DNS 缓存与上游连接", "info");
    }

    private void updateNotification() {
        long now = System.currentTimeMillis();
        if (now - lastNotifyAt < 2500) return;
        lastNotifyAt = now;
        TrafficStats.flush(this);
        Stats.flush(this);
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification());
        } catch (Exception ignored) {
        }
    }

    /** 按当前配置构造隧道参数：首次建立与自愈重建共用，避免两处参数漂移。 */
    private Builder buildTun() {
        Builder b = new Builder();
        b.setSession("GGBot");
        b.addAddress(TUN_IP, 32);
        b.addDnsServer(DnsPacket.DNS_IP);
        // 整段虚拟池一起路由进隧道：DNS 地址 198.18.0.1 与解密用的 198.18.1.0/24 都在其中。
        // 其余网段不接管，普通上网流量依旧走物理网卡。
        b.addRoute("198.18.0.0", 16);
        b.setMtu(1500);
        // 不要调用 setBlocking(false)：非阻塞模式下 read() 会立刻返回 0，读循环会空转烧 CPU
        try {
            if (!Config.routeSelf(this)) b.addDisallowedApplication(getPackageName());
        } catch (Exception ignored) {
        }
        Set<String> bypass = Config.bypass(this);
        for (String pkg : bypass) {
            try {
                b.addDisallowedApplication(pkg);
            } catch (Exception ignored) {
            }
        }
        return b;
    }

    private void startVpn() {
        if (running) return;
        stopRequested = false;
        // 防御性收尾：上一轮的资源若还挂着（异常路径没走到 shutdown），先清掉再重建，
        // 否则 startVpn 直接覆盖字段，旧线程池和 TCP 连接就永久泄漏了
        if (pool != null) {
            try {
                pool.shutdownNow();
            } catch (Exception ignored) {
            }
            pool = null;
        }
        if (tcp != null) {
            try {
                tcp.shutdown();
            } catch (Exception ignored) {
            }
            tcp = null;
        }
        // 规则库放后台载入：载入期间查询照常转发，只是暂时不拦，不能把网络掐了
        Rules.loadAsync(getApplicationContext(), null);

        Builder b = buildTun();

        try {
            tunFd = b.establish();
        } catch (Exception e) {
            tunFd = null;
            Diag.push("establish failed: " + e, "error");
        }
        if (tunFd == null) {
            // establish() 未授权时直接返回 null 而不抛异常，必须显式留痕，否则无从排查。
            Diag.push("隧道未建立：VPN 授权被拒或参数非法", "error");
            running = false;
            Config.setEnabled(this, false);
            shutdown();
            return;
        }

        tunIn = new FileInputStream(tunFd.getFileDescriptor());
        tunOut = new FileOutputStream(tunFd.getFileDescriptor());
        pool = Executors.newFixedThreadPool(6);
        // 轻量 / 只跳过档不建转发引擎：没有虚拟池连接要接，少一个线程池与一堆 fd
        tcp = Power.allowsTcp(Power.current(this)) ? new TcpProxy(this, this) : null;
        running = true;
        Config.setEnabled(this, true);

        // 息屏时持有 PARTIAL_WAKE_LOCK：没有它，屏幕一灭 CPU 就睡，
        // 夜里后台的 DNS 查询全部排队等唤醒，应答动辄慢几秒
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ggbot:dns");
                wl.setReferenceCounted(false);
                wl.acquire();
            }
        } catch (Exception ignored) {
        }

        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkRequest req = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
                netCb = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network n) {
                        netChanged();
                    }

                    @Override
                    public void onLost(Network n) {
                        netChanged();
                    }
                };
                cm.registerNetworkCallback(req, netCb);
                cmRef = cm;
            }
        } catch (Exception ignored) {
        }

        reader = new Thread(new Reader(), "ggbot-dns");
        reader.start();
        guard = new Thread(new Guard(), "ggbot-guard");
        guard.start();
    }

    private final class Reader implements Runnable {
        @Override
        public void run() {
            byte[] buf = new byte[32768];
            while (running && !stopRequested) {
                FileInputStream in = tunIn;
                if (in == null) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }
                int n;
                try {
                    n = in.read(buf);
                } catch (Exception e) {
                    if (stopRequested) break;
                    // 旧 fd 被关闭（原地重建）或 tun 被系统拆掉：就地重建后接着读。
                    // 1.3.0 走的是「先 shutdown 再拉服务」，前台服务断档会让系统把
                    // 无障碍绑定一起摘掉（荣耀 MagicOS 11 实测），所以不能再那么干。
                    rebuildTun();
                    continue;
                }
                if (n <= 0) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }
                TrafficStats.addTx(n);
                try {
                    handle(buf, n);
                } catch (Exception ignored) {
                }
                updateNotification();
            }
            if (stopRequested) shutdown();
        }
    }

    /* ---------------- 报文处理 ---------------- */

    private void handle(byte[] p, int len) {
        if (len < 28) return;
        int vihl = p[0] & 0xff;
        if ((vihl >> 4) != 4) return;
        int ihl = (vihl & 0x0f) * 4;
        // 这里只能卡到「最小传输层头」：UDP 头是 8 字节，TCP 头是 20 字节。
        // 早先一律按 12 字节卡，1 字节载荷的 UDP 包（29 字节）会被当成坏包丢掉，
        // 看上去像「QUIC 请求石沉大海」，实际是入口就没了。
        if (ihl < 20 || len < ihl + 8) return;

        int proto = p[9] & 0xff;
        if (proto == 6) {                       // TCP：交给转发引擎
            if (len < ihl + 20) return;
            TcpProxy t = tcp;
            if (t != null) t.handle(p, len);
            return;
        }
        if (proto != 17) return;                // 其余协议一律不接管

        // 发往虚拟池的 UDP 几乎都是 QUIC（HTTP/3）。隧道里只有 TCP 转发，
        // 直接丢弃的代价是客户端要等自己的重传超时（几百毫秒到数秒）才回落 TCP，
        // 这期间页面是白的。回一个 ICMP 端口不可达，内核立刻把 ECONNREFUSED
        // 交给对应的 UDP socket，应用下一毫秒就改走 TCP。
        if (FakeIp.inPool(new byte[]{p[16], p[17], p[18], p[19]})) {
            QUIC_DENIED.incrementAndGet();
            byte[] icmp = Icmp.portUnreachable(p, len);
            if (icmp != null) writeTun(icmp);
            return;
        }

        int dstPort = ((p[ihl + 2] & 0xff) << 8) | (p[ihl + 3] & 0xff);
        if (dstPort != 53) return;
        int srcPort = ((p[ihl] & 0xff) << 8) | (p[ihl + 1] & 0xff);
        int udpLen = ((p[ihl + 4] & 0xff) << 8) | (p[ihl + 5] & 0xff);

        int dnsOff = ihl + 8;
        int dnsLen = Math.min(udpLen - 8, len - dnsOff);
        if (dnsLen <= 12) return;

        String name = DnsPacket.parseQuestion(p, dnsOff, dnsLen);
        if (name == null) return;
        int qEnd = DnsPacket.questionEnd(p, dnsOff, dnsLen);
        if (qEnd < 0) return;
        int qtype = DnsPacket.qtype(p, qEnd);

        byte[] srcIp = new byte[]{p[12], p[13], p[14], p[15]};
        QUERIES.incrementAndGet();
        Stats.addQuery(this);

        // 命中解密规则的域名：不再返回真实地址，而是指向虚拟池，
        // 这样只有它的 TCP 连接会进隧道，其余流量完全不受影响。
        // 直通名单里的域名不再返回虚拟地址：它的证书校验过不去，解密只会换来白屏
        boolean decryptHost = Power.allowsMitm(Power.current(this)) && Config.mitm(this)
                && (Config.debugAll(this) || Domains.matches(decryptNow(), name))
                && !MitmBypass.bypassed(name);

        if (decryptHost && qtype == 1) {
            String vip = FakeIp.ipFor(name);
            if (vip != null) {
                byte[] resp = DnsPacket.buildAnswerIp(p, dnsOff, dnsLen, DnsPacket.toIp(vip));
                if (resp != null) {
                    writeTun(DnsPacket.buildIpUdp(resp, srcIp, srcPort));
                    QueryLog.add(name, QueryLog.DECRYPT);
                    return;
                }
            }
        }

        if (Rules.isBlocked(name)) {
            BLOCKED.incrementAndGet();
            Stats.addBlock(this);
            pushBlock(DnsBlock.rule(name, Rules.blockReason(name)));
            QueryLog.add(name, QueryLog.BLOCK);
            byte[] resp = Config.mode() == Config.MODE_NULL
                    ? DnsPacket.buildNullAnswer(p, dnsOff, dnsLen, qtype)
                    : DnsPacket.buildError(p, dnsOff, dnsLen, 3);
            if (resp != null) writeTun(DnsPacket.buildIpUdp(resp, srcIp, srcPort));
            return;
        }

        // 隧道只承载 IPv4。AAAA 若照实回真实 IPv6 地址，应用会直接走 IPv6 出去，
        // 整条隧道（包括解密）被静默绕过——日志里一切正常，广告却照样加载。
        // 这里回 NODATA（NOERROR、零条答案），标准解析器会立刻改用 A 记录。
        if (decryptHost && qtype == 28) {
            IPV6_SUPPRESSED.incrementAndGet();
            byte[] resp = DnsPacket.buildError(p, dnsOff, dnsLen, 0);
            if (resp != null) writeTun(DnsPacket.buildIpUdp(resp, srcIp, srcPort));
            return;
        }

        String key = name + "|" + qtype;
        byte[] hit = cache.get(key);
        if (hit != null) {
            byte[] resp = hit.clone();
            resp[0] = p[dnsOff];
            resp[1] = p[dnsOff + 1];
            writeTun(DnsPacket.buildIpUdp(resp, srcIp, srcPort));
            QueryLog.add(name, QueryLog.ALLOW);
            return;
        }

        final byte[] q = Arrays.copyOfRange(p, dnsOff, dnsOff + dnsLen);
        ExecutorService ex = pool;
        if (ex != null && !ex.isShutdown()) {
            ex.execute(new Resolver(q, key, name, qtype, srcIp, srcPort));
        }
    }

    /**
     * 上游查询。
     *
     * 一次查询要过五道关，每一道都对应一个真实存在的问题：
     *   合并    同一域名被十几个进程同时问，只放一个出去
     *   TTL     按应答自带的 TTL 缓存，而不是一律两分钟
     *   负缓存  NXDOMAIN 也要记，否则垃圾域名每次都要再问一遍
     *   CNAME   拆 CNAME 链，拦住「第一方域名指向追踪域」这种隐身写法
     *   兜底    上游失联时拿过期记录顶一下，好过整机断网
     */
    private final class Resolver implements Runnable {
        private final byte[] query;
        private final String key;
        private final String name;
        private final int qtype;
        private final byte[] clientIp;
        private final int clientPort;

        Resolver(byte[] query, String key, String name, int qtype,
                 byte[] clientIp, int clientPort) {
            this.query = query;
            this.key = key;
            this.name = name;
            this.qtype = qtype;
            this.clientIp = clientIp;
            this.clientPort = clientPort;
        }

        @Override
        public void run() {
            Coalescer.Ticket ticket = coalescer.begin(key);
            if (!ticket.owner) {
                byte[] shared = ticket.await(Coalescer.WAIT_MS);
                if (shared == BLOCKED_MARK) {
                    // owner 已判定这条查询要按 CNAME 链拦下。合并等待者必须拿到同一个
                    // 判定结果——早先把未过滤的原始应答直接转发给等待者，十个并发里
                    // 九个绕过了 CNAME 拦截。
                    BLOCKED.incrementAndGet();
                    Stats.addBlock(DnsVpnService.this);
                    pushBlock(DnsBlock.rule(name, "CNAME 链（合并）"));
                    QueryLog.add(name, QueryLog.BLOCK);
                    replyBlocked();
                    return;
                }
                if (shared != null) {
                    reply(shared);
                    return;
                }
                // 等超时/上游失败就自己去查；但不能去 finish 别人的槽位，否则会把真正的主人挤掉
            }

            byte[] resp = null;
            boolean owner = ticket.owner;
            boolean settled = false;
            try {
                resp = upstream();
                // 上游无视 EDNS 钳制回了超过隧道 MTU 的包：单包写入 tun 会被丢，
                // 客户端只见超时。回 TC 位让它改走 TCP 重查（TCP 到 53 端口正常转发）。
                if (resp != null && resp.length > MAX_UDP_RESP) {
                    resp = DnsPacket.buildTruncated(query, 0, query.length);
                }
                if (resp != null) {
                    // CNAME 判定必须发生在 finish() 之前：发布出去的就是最终结果
                    String hop = Rules.cnameHit(DnsPacket.cnameChain(resp, 0, resp.length));
                    if (hop != null) {
                        if (owner) {
                            coalescer.finish(key, BLOCKED_MARK);
                            settled = true;
                        }
                        CNAME.incrementAndGet();
                        BLOCKED.incrementAndGet();
                        Stats.addBlock(DnsVpnService.this);
                        pushBlock(DnsBlock.cname(name, hop));
                        QueryLog.add(name, QueryLog.BLOCK);
                        replyBlocked();
                        return;
                    }
                    cache.put(key, resp, DnsPacket.minTtl(resp, 0, resp.length));
                }
                if (owner) {
                    coalescer.finish(key, resp);
                    settled = true;
                }
            } finally {
                // 兜底：owner 侧任何异常也要放行等待者（finish 幂等，重复调用无害）
                if (owner && !settled) coalescer.finish(key, resp);
            }

            if (resp == null) {
                byte[] old = cache.stale(key);
                if (old != null) {
                    // 过期记录也比重试四秒再超时强，但要把 TTL 压到 5 秒，别让客户端长期吃旧地址
                    DnsPacket.stampTtl(old, 0, old.length, DnsCache.STALE_TTL_S);
                    reply(old);
                    return;
                }
                byte[] fail = DnsPacket.buildError(query, 0, query.length, 2);
                if (fail != null) writeTun(DnsPacket.buildIpUdp(fail, clientIp, clientPort));
                return;
            }

            reply(resp);
        }

        /**
         * 上游查询：DoH 开着先走加密（做法参考 PersonalDNSFilter bootstrapDNS），
         * 失败（断网/端点被墙）回落明文 UDP，宁可明文也不断网。
         * 加密走通时链路上的明文 UDP:53 不再出现，被污染网络里解析结果更可信
         * （2026-10-29 第 13 轮实测里 google.com 曾被污染到 157.240.7.20）。
         */
        private byte[] upstream() {
            if (Config.doh()) {
                byte[] r = DoH.query(query, Config.dohUrl());
                if (DnsPacket.validUpstreamResponse(query, r)) return r;
            }
            DatagramSocket s = null;
            try {
                s = new DatagramSocket();
                if (!protect(s)) return null;
                s.setSoTimeout(4000);
                InetAddress up = InetAddress.getByName(Config.upstream());
                // 客户端原查询常声明 4096 的 EDNS 缓冲，钳到 1232 防止上游回超 MTU 大包
                DnsPacket.clampEdnsSize(query, 0, query.length);
                s.send(new DatagramPacket(query, query.length, up, 53));
                byte[] rbuf = new byte[4096];
                DatagramPacket rp = new DatagramPacket(rbuf, rbuf.length);
                s.receive(rp);
                byte[] out = Arrays.copyOfRange(rbuf, 0, rp.getLength());
                // ID/QR/长度校验：明文 UDP 谁都能伪造。不校验 ID 等于给缓存投毒开门，
                // 0 字节垃圾更会被当成合并查询的拦截哨兵，把没拦的域名误判成拦截
                return DnsPacket.validUpstreamResponse(query, out) ? out : null;
            } catch (Exception e) {
                return null;
            } finally {
                if (s != null) {
                    try {
                        s.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        /** 把应答交给客户端。事务 ID 用客户端自己那一份，合并查询时各家的 ID 并不相同。 */
        private void reply(byte[] resp) {
            if (resp == null || resp.length < 12) return;
            QueryLog.add(name, QueryLog.ALLOW);
            byte[] out = resp.clone();
            out[0] = query[0];
            out[1] = query[1];
            writeTun(DnsPacket.buildIpUdp(out, clientIp, clientPort));
        }

        private void replyBlocked() {
            byte[] resp = Config.mode() == Config.MODE_NULL
                    ? DnsPacket.buildNullAnswer(query, 0, query.length, qtype)
                    : DnsPacket.buildError(query, 0, query.length, 3);
            if (resp != null) writeTun(DnsPacket.buildIpUdp(resp, clientIp, clientPort));
        }
    }


    /** 解密域名列表变动频繁度低，隔两秒才重读一次 SharedPreferences。 */
    private Set<String> decryptNow() {
        long now = System.currentTimeMillis();
        if (now - decryptAt > 2000L) {
            decryptSet = Config.decrypt(this);
            decryptAt = now;
        }
        return decryptSet;
    }

    /** TcpProxy.Sink 的实现：转发引擎把组装好的包交回隧道。 */
    @Override
    public void write(byte[] pkt) {
        writeTun(pkt);
    }

    /** 把组装好的包写回隧道，多线程共用同一个输出流。 */
    private void writeTun(byte[] pkt) {
        FileOutputStream o = tunOut;
        if (o == null) return;
        TrafficStats.addRx(pkt.length);
        try {
            synchronized (writeLock) {
                o.write(pkt);
            }
        } catch (Exception ignored) {
        }
    }

    /* ---------------- 收尾 ---------------- */

    private void shutdown() {
        stopRequested = true;
        running = false;
        try {
            if (cmRef != null && netCb != null) cmRef.unregisterNetworkCallback(netCb);
        } catch (Exception ignored) {
        }
        cmRef = null;
        netCb = null;
        TrafficStats.flush(this);
        Stats.flush(this);
        cache.clear();
        coalescer.clear();
        if (tcp != null) {
            tcp.shutdown();
            tcp = null;
        }
        RealResolver.clear();
        FakeIp.clear();
        try {
            if (reader != null) reader.interrupt();
        } catch (Exception ignored) {
        }
        try {
            if (guard != null) guard.interrupt();
        } catch (Exception ignored) {
        }
        guard = null;
        if (wl != null) {
            try {
                wl.release();
            } catch (Exception ignored) {
            }
            wl = null;
        }
        try {
            if (pool != null) pool.shutdownNow();
        } catch (Exception ignored) {
        }
        try {
            if (tunIn != null) tunIn.close();
        } catch (Exception ignored) {
        }
        try {
            if (tunOut != null) tunOut.close();
        } catch (Exception ignored) {
        }
        try {
            if (tunFd != null) tunFd.close();
        } catch (Exception ignored) {
        }
        tunIn = null;
        tunOut = null;
        tunFd = null;
        reader = null;
        pool = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /* ---------------- 自愈守卫 ---------------- */

    /** 上次自愈重建的时刻。 */
    private static volatile long lastRestartAt = 0L;

    /**
     * tun 网卡守卫（做法来自 AdAway 的 VpnConnectionMonitor：每 10 秒看网卡还在不在）。
     *
     * 1.3.0 的重建走「Config 关 → shutdown → 重新 startForegroundService」，每次都会让
     * 前台服务断档；真机（荣耀 MagicOS 11 / Android 17）实测这个窗口会把无障碍绑定带走，
     * 用户看到的就是「无障碍老是自己关」。现在只在服务内部换 fd，前台与唤醒锁都不动。
     */
    private final class Guard implements Runnable {
        @Override
        public void run() {
            while (running && !stopRequested) {
                try {
                    Thread.sleep(TunGuard.CHECK_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (!running || stopRequested) return;
                // 顺手巡检无障碍：被系统摘掉且已授权就写回（荣耀/华为常见）
                A11y.selfHeal(DnsVpnService.this);
                // 另一条路：名单里还在、事件却断了（ContentObserver 看不见的僵尸绑定）
                A11y.healStale(DnsVpnService.this);
                // 零权限兜底：healStale 要写权限或 Shizuku；都没有时荣耀的「挂起型僵尸」
                // 用 disableSelf + FSI 引导重开（2026-11-13 荣耀 BRT-AN00 专项）
                SkipService.suicideIfZombie(DnsVpnService.this);
                if (!TunGuard.shouldRestart(true, false, TunGuard.anyTunUp())) continue;
                Diag.push("tun 网卡不在了，原地重建引擎", "warn");
                rebuildTun();
            }
        }
    }

    /**
     * 原地重建隧道：只换 fd，不停前台服务、不释放唤醒锁、不动线程池与规则快照。
     * 返回是否真的重建成功。
     */
    private synchronized boolean rebuildTun() {
        if (stopRequested || !running) return false;
        long now = System.currentTimeMillis();
        if (!TunGuard.gapOk(now, lastRestartAt)) return false;
        lastRestartAt = now;

        String a11ySaved = A11y.snapshot(this);
        closeTunQuietly();
        ParcelFileDescriptor fd;
        try {
            fd = buildTun().establish();
        } catch (Exception e) {
            fd = null;
            Diag.push("原地重建 establish 异常: " + e, "error");
        }
        if (fd == null) {
            // 授权被撤或参数非法：这种是真死，照实收尾，别在原地打转
            Diag.push("原地重建失败：VPN 授权被拒或参数非法", "error");
            Config.setEnabled(this, false);
            shutdown();
            return false;
        }
        tunFd = fd;
        tunIn = new FileInputStream(fd.getFileDescriptor());
        tunOut = new FileOutputStream(fd.getFileDescriptor());
        Diag.push("tun 已原地重建，前台服务未中断", "info");
        A11y.restoreIfNeeded(this, a11ySaved);
        return true;
    }

    /** 只关隧道 fd，其余（前台通知、唤醒锁、线程池、TCP 连接）原样保留。 */
    private void closeTunQuietly() {
        try {
            if (tunIn != null) tunIn.close();
        } catch (Exception ignored) {
        }
        try {
            if (tunOut != null) tunOut.close();
        } catch (Exception ignored) {
        }
        try {
            if (tunFd != null) tunFd.close();
        } catch (Exception ignored) {
        }
        tunIn = null;
        tunOut = null;
        tunFd = null;
    }

    @Override
    public void onRevoke() {
        Config.setEnabled(this, false);
        A11yWatch.unregister(this);
        shutdown();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        A11yWatch.unregister(this);
        shutdown();
        super.onDestroy();
    }
}
