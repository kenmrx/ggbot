package com.ggbot.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;

/**
 * 通过 Shizuku 写回无障碍绑定——没有 WRITE_SECURE_SETTINGS 时的替代路径。
 *
 * 为什么需要这条：无障碍绑定被系统摘掉后要写回 enabled_accessibility_services，
 * 那是 Secure 设置，普通应用没权限。拿这个权限只有三条路：
 *   1. 系统签名（做不到）
 *   2. adb shell pm grant（要电脑，用户嫌麻烦）
 *   3. Shizuku（给的是 shell/root 级 Binder，等价于 2，但手机上点一下就行）
 * DSH-Go 走的是第 3 条，所以它"开一次就常驻"。这条就是给它补上同一个能力。
 *
 * 关键实现：不调 Shizuku 的 Java API 去拼业务逻辑，而是拿它代理的
 * "settings" 系统服务 Binder，直接发 PUT 事务。理由：
 *   - Shizuku 的 shell 权限落在 settings 服务上，直接 transact 最贴近 adb 的
 *     `settings put secure <key> <value>`，行为完全一致，没有中间层语义差异
 *   - 不依赖 Shizuku 的高层封装版本（api 版本升级不影响这条路径）
 *   - 全程只需一个 IBinder + 一次 transact，没有引入额外线程或服务
 *
 * 失败一律静默降级：没装 Shizuku、没授权、Binder 断了，都只是返回 false，
 * 上层继续走"发通知引导用户手动拨"的既有路径，不影响任何现有功能。
 */
public final class ShizukuHeal {

    private static final String TAG = "GGBot.Shizuku";

    /** Shizuku 主包名。 */
    public static final String PKG = "moe.shizuku.privileged.api";
    /** Shizuku 的授权权限名（用户在 Shizuku 里点"允许"后，本应用才拿到 Binder）。 */
    public static final String PERM = "moe.shizuku.manager.permission.API_V23";

    private ShizukuHeal() {
    }

    /**
     * Shizuku 可用吗：装了 + Binder 已送达且活着 + 平台权限已授。
     *
     * Binder 是否送达由 server 决定（授权后才送，见 ShizukuGate）；平台权限
     * moe.shizuku.manager.permission.API_V23 在用户点「始终允许」时由 server
     * 以 shell 身份 pm grant 给我们——所以 PackageManager 这一项就是授权判据
     * （官方文档同样推荐用平台权限检查授权状态）。任何一步异常都当"不可用"。
     */
    public static boolean available(Context c) {
        if (!installed(c)) return false;
        try {
            if (!ShizukuGate.pingBinder()) return false;
            return c.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 只判断装没装（不触发 Shizuku 的类加载，设置页展示用）。 */
    public static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 拼出写设置的命令行。argv 直传 exec，不过 shell，key/value 里的
     * 空格、引号、分号都不会被解释——独立成纯函数是为了让门闸能直接断言
     * 它的形状（历史上第一版在这里发手搓 Parcel，协议是猜的，真机必败）。
     */
    public static String[] cmdFor(String key, String value) {
        return new String[]{"settings", "put", "secure", key, value};
    }
    /*
     * Shizuku 的 binder 协议常量。来源：本机 Shizuku manager 13.6.0 APK 反编译出的
     * 服务端 dispatch（moe.shizuku.server.IShizukuService$Stub / IRemoteProcess$Stub），
     * 与设备上正在跑的 server（同版本系）逐一配对，不是猜的：
     *   IShizukuService.newProcess(String[] cmd, String[] env, String dir) = 事务 8
     *     parcel: token + writeStringArray(cmd) + writeStringArray(env) + writeString(dir)
     *     reply : readException + readStrongBinder(IRemoteProcess)
     *   IRemoteProcess.waitForTimeout(long, TimeUnit) = 事务 8
     *     parcel: token + writeLong(时长) + writeString(TimeUnit 名)
     *     reply : readException + readInt(1=已退出 0=超时)
     *   IRemoteProcess.exitValue() = 事务 5；destroy() = 事务 6
     * server 对旧版 AIDL 面向后兼容（DSH-Go 的同族旧 stub 也在同一 server 上工作）。
     */
    private static final String TOKEN_SERVICE = "moe.shizuku.server.IShizukuService";
    private static final String TOKEN_REMOTE_PROCESS = "moe.shizuku.server.IRemoteProcess";
    private static final int TX_NEW_PROCESS = 8;
    private static final int TX_REMOTE_WAIT_TIMEOUT = 8;
    private static final int TX_REMOTE_EXIT_VALUE = 5;
    private static final int TX_REMOTE_DESTROY = 6;
    /** IShizukuService.requestPermission(int)：server 收到后拉起 manager 的授权对话框（void 回包）。 */
    private static final int TX_REQUEST_PERMISSION = 15;
    /** 命令最长等这么久；settings put 正常毫秒级返回，超时视为失败并杀掉远端进程。 */
    private static final long CMD_TIMEOUT_MS = 10_000L;

    /**
     * 通过 Shizuku 写 Secure 设置，等价于 adb 的
     * `settings put secure <key> <value>`。
     *
     * 实现：向 Shizuku server 发 IShizukuService.newProcess 事务，让 server（shell 身份）
     * 起 `settings` 进程并等它退出、取真实退出码。历史上这里直接对 "settings" 系统服务
     * 发手搓 Parcel，接口名与事务码都是无出处的猜测，真机上从未走通过——
     * 现在这条路与 DSH-Go 在同设备上实测可用的机制同源（shell 子进程），
     * 且逐字段对照过本机 server 的 AIDL dispatch。
     */
    public static boolean putSecure(String key, String value) {
        try {
            IBinder service = ShizukuGate.binder();
            if (service == null || !service.pingBinder()) return false;

            IBinder remote;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(TOKEN_SERVICE);
                data.writeStringArray(cmdFor(key, value));
                data.writeStringArray(null);   // env
                data.writeString(null);        // dir
                if (!service.transact(TX_NEW_PROCESS, data, reply, 0)) return false;
                reply.readException();
                remote = reply.readStrongBinder();
            } finally {
                data.recycle();
                reply.recycle();
            }
            if (remote == null || !remote.pingBinder()) return false;

            try {
                if (!remoteExited(remote, CMD_TIMEOUT_MS)) {
                    transactVoid(remote, TX_REMOTE_DESTROY);
                    android.util.Log.w(TAG, "settings put 超时，已销毁远端进程");
                    return false;
                }
                return transactExitCode(remote) == 0;
            } catch (android.os.RemoteException e) {
                // server 会在调用方进程死亡时回收子进程，这里收尾失败只记日志
                android.util.Log.w(TAG, "远端进程等待失败: " + e);
                return false;
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "putSecure 失败: " + t);
            return false;
        }
    }

    /** IRemoteProcess.waitForTimeout：1=已退出，0=超时。 */
    private static boolean remoteExited(IBinder remote, long timeoutMs) throws android.os.RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TOKEN_REMOTE_PROCESS);
            data.writeLong(timeoutMs);
            data.writeString("MILLISECONDS");
            if (!remote.transact(TX_REMOTE_WAIT_TIMEOUT, data, reply, 0)) return false;
            reply.readException();
            return reply.readInt() == 1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** IRemoteProcess.exitValue：只有进程已退出才有效，否则 server 侧抛 IllegalStateException。 */
    private static int transactExitCode(IBinder remote) throws android.os.RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TOKEN_REMOTE_PROCESS);
            if (!remote.transact(TX_REMOTE_EXIT_VALUE, data, reply, 0)) return -1;
            reply.readException();
            return reply.readInt();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** 只发事务不取结果（destroy 用）。失败不抛——收尾路径失败没有意义。 */
    private static void transactVoid(IBinder remote, int code) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TOKEN_REMOTE_PROCESS);
            remote.transact(code, data, reply, 0);
            reply.readException();
        } catch (Throwable ignored) {
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * 请求 Shizuku 授权（server 会拉起 manager 的授权对话框）。
     *
     * 为什么不走 Shizuku.requestPermission：api jar 是从 DSH-Go 抠的残缺版，
     * 那条路要加载 moe.shizuku.server 的 AIDL stub（jar 里没有），
     * 运行时必抛 NoClassDefFoundError——设置页的「Shizuku 授权」按钮
     * 因此永远只能弹「授权请求失败」。这里直接对 server binder 发事务 15，
     * 与本机 manager 13.6 的 dispatch 逐一核对过。
     */
    public static boolean requestPermission(int requestCode) {
        try {
            IBinder service = ShizukuGate.binder();
            if (service == null || !service.pingBinder()) return false;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(TOKEN_SERVICE);
                data.writeInt(requestCode);
                if (!service.transact(TX_REQUEST_PERMISSION, data, reply, 0)) return false;
                reply.readException();
                return true;
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "requestPermission 失败: " + t);
            return false;
        }
    }

    /**
     * 判断能不能用 Shizuku 走写回，给界面文案用（纯逻辑，桌面测试可验）。
     *
     * 为什么不直接调 available()：界面文案应当在"没装 Shizuku"和
     * "装了但没授权"时说不同的话，否则用户不知道该装还是该去授权。
     */
    public static int stateOf(boolean installed, boolean binderAlive, boolean granted) {
        if (!installed) return ST_NONE;
        if (!binderAlive) return ST_NOT_RUNNING;
        return granted ? ST_READY : ST_NOT_GRANTED;
    }

    public static final int ST_NONE = 0;
    public static final int ST_NOT_RUNNING = 1;
    public static final int ST_NOT_GRANTED = 2;
    public static final int ST_READY = 3;

    /** 各状态对应的下一步动作。 */
    public static String textOf(int state) {
        switch (state) {
            case ST_READY:
                return "Shizuku 已就绪：无障碍被摘掉时会自动写回";
            case ST_NOT_GRANTED:
                return "Shizuku 已运行，但没授权给 GGBot：去 Shizuku 里允许一次";
            case ST_NOT_RUNNING:
                return "Shizuku 装了但没启动：打开 Shizuku 让它跑起来";
            default:
                return "未安装 Shizuku（装了它就能免电脑自动写回）";
        }
    }

    /**
     * 有没有任何一条自动写回的路可用：
     * 自家有 WRITE_SECURE_SETTINGS，或者 Shizuku 能代理。
     */
    public static boolean canAutoHeal(Context c) {
        return A11y.canWrite(c) || available(c);
    }

    /**
     * 只走 Shizuku 写回。
     *
     * 注意：这里**不**替自家权限做短路。原来写 `if (canWrite) return true` 是错的——
     * 那条路径下本方法什么也没写，却对着调用方报成功，一旦调用方据此认为"已写回"
     * 就会出现"报了成功、实际没动"的静默失效。要不要用自家权限由调用方决定，
     * 本方法只负责它自己这条（Shizuku），失败就如实返回 false。
     *
     * 先写列表再写总开关：顺序反了的话，总开关已置 1、列表还没到位，
     * 系统会有一瞬间认为"无障碍开着但没有服务"——某些机型会把这个瞬间当成
     * 异常状态并重置绑定。
     */
    public static boolean writeBack(String fullList, boolean enable) {
        if (!putSecure("enabled_accessibility_services", fullList)) return false;
        return putSecure("accessibility_enabled", enable ? "1" : "0");
    }
}
