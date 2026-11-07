package com.ggbot.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;

import moe.shizuku.api.BinderContainer;

/**
 * Shizuku 的 Binder 接收点（自实现，替代 rikka.shizuku.ShizukuProvider）。
 *
 * 协议：Shizuku server 以 shell 身份 call 本 provider（authority 固定为
 * 「包名.shizuku」），method=sendBinder 时 Bundle 里带一个
 * moe.shizuku.api.BinderContainer；我们只负责把 IBinder 收下来存静态。
 * 授权与否由 server 在每个事务上自行校验，与 provider 实现无关——
 * 所以这个实现可以完全不管官方的授权状态机。
 *
 * 为什么自实现：官方 provider 把 binder 交给 rikka.shizuku.Shizuku 静态态，
 * 而本项目手里的 shizuku-api jar 缺 AIDL stub，rikka.shizuku.Shizuku 类
 * 一加载就 NoClassDefFoundError 且被进程永久缓存（实测日志 Rejecting
 * re-init on previously-failed class），连 pingBinder 都永远失败。
 * 自实现后运行时不再依赖那份残缺 jar 的任何类。
 */
public class ShizukuGate extends ContentProvider {

    /** server 发来的 sendBinder 附加键（与官方常量逐字相同）。 */
    private static final String EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";
    private static final String METHOD_SEND_BINDER = "sendBinder";
    private static final String METHOD_GET_BINDER = "getBinder";

    private static volatile IBinder sBinder;
    /** 本应用包名（attachInfo 时缓存，attach 协议要带上）。 */
    private static volatile String sPackage = "com.ggbot.app";
    /** attach 回执里的授权状态（server 侧真实授权结论，比平台权限查询更直接）。 */
    private static volatile boolean sAttachPermissionGranted;
    /** 最近一次授权对话框的确认结果；-1=还没有过。 */
    private static volatile int sLastConfirmationResult = -1;

    /** server 送来的 binder；未授权时 server 不送，返回 null。 */
    public static IBinder binder() {
        return sBinder;
    }

    /** binder 在且活着（轻探测，无 Parcel）。 */
    public static boolean pingBinder() {
        return pingBinder(sBinder);
    }

    static boolean pingBinder(IBinder b) {
        return b != null && b.pingBinder();
    }

    /** attach 回执：server 认为本应用当前是否已授权。 */
    public static boolean attachReplyGranted() {
        return sAttachPermissionGranted;
    }

    /** 最近一次授权对话框结果（-1 无；1 允许；0 拒绝）。 */
    public static int lastConfirmationResult() {
        return sLastConfirmationResult;
    }

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);
        // 与官方 provider 相同的两个硬约束：server 侧按这两个前提投递
        if (info.multiprocess)
            throw new IllegalStateException("android:multiprocess must be false");
        if (!info.exported)
            throw new IllegalStateException("android:exported must be true");
        sPackage = context.getPackageName();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @SuppressWarnings("deprecation")
    private static <T extends android.os.Parcelable> T getParcelableCompat(Bundle b, String key) {
        return b.getParcelable(key);
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (extras == null) return null;
        extras.setClassLoader(BinderContainer.class.getClassLoader());
        Bundle reply = new Bundle();
        if (METHOD_SEND_BINDER.equals(method)) {
            // getParcelable(String) 33 起废弃，但 minSdk 24 下类型化版本不存在；集中在一个函数里压告警
            BinderContainer c = getParcelableCompat(extras, EXTRA_BINDER);
            if (c != null && c.binder != null) {
                // server 在一次进程启动里可能送两次 binder（实测）；attach 带有
                // server 侧的记录竞态（重复 attach 会 NPE），每个 binder 只 attach 一次
                if (c.binder != sBinder) {
                    sBinder = c.binder;
                    attachSelf(c.binder);
                }
            }
            return reply;
        }
        if (METHOD_GET_BINDER.equals(method)) {
            IBinder b = sBinder;
            if (b != null && b.pingBinder()) {
                reply.putParcelable(EXTRA_BINDER, new BinderContainer(b));
            }
            return reply;
        }
        return null;
    }

    /*
     * 收到 binder 后必须先把「自己」attach 给 server（事务 18），
     * 否则后续一切事务都报 Not an attached client（实测）。attach 的
     * Parcel 形状逐字段对照本机 manager 13.6 的客户端实现（zg0.d）：
     *   token(IShizukuService) + writeStrongBinder(我方回调) + writeInt(1) + writeBundle
     *   bundle: shizuku:attach-api-version=13、shizuku:attach-package-name=包名
     * server 随后回调我方 binder：事务 2 = attach 回执（含 permission-granted），
     * 事务 3 = 授权对话框确认结果。
     */
    private static void attachSelf(IBinder server) {
        IBinder app = new AppCallback();
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService");
            data.writeStrongBinder(app);
            data.writeInt(1);
            Bundle b = new Bundle();
            b.putInt("shizuku:attach-api-version", 13);
            b.putString("shizuku:attach-package-name", sPackage);
            b.writeToParcel(data, 0);
            server.transact(18, data, reply, 0);
            reply.readException();
        } catch (Throwable t) {
            android.util.Log.w("GGBot.Shizuku", "attach 失败: " + t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * 我方的 IShizukuApplication 本地实现。server 只会单向推送（oneway），
     * 事务形状逐字段对照 manager 13.6 的客户端 stub（Kn.onTransact/Xe 代理）。
     */
    private static final class AppCallback extends android.os.Binder {
        @Override
        protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags)
                throws android.os.RemoteException {
            if (code == 1598968902) {
                reply.writeString("moe.shizuku.server.IShizukuApplication");
                return true;
            }
            if (code >= 1 && code <= 16777215) {
                data.enforceInterface("moe.shizuku.server.IShizukuApplication");
            }
            if (code == 2) {
                Bundle b = data.readInt() != 0 ? data.readBundle(BinderContainer.class.getClassLoader()) : null;
                if (b != null) {
                    sAttachPermissionGranted =
                            b.getBoolean("shizuku:attach-reply-permission-granted", false);
                }
                return true;
            }
            if (code == 3) {
                int req = data.readInt();
                Bundle b = data.readInt() != 0 ? data.readBundle(BinderContainer.class.getClassLoader()) : null;
                sLastConfirmationResult = req;
                if (b != null) {
                    boolean granted = b.getBoolean("shizuku:attach-reply-permission-granted",
                            b.getBoolean("granted", false));
                    sAttachPermissionGranted = granted;
                }
                return true;
            }
            if (code == 10001) {
                data.readInt();
                data.readInt();
                data.readString();
                data.readInt();
                if (reply != null) reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    // server 只用 call；其余方法按官方实现返回空
    @Override
    public final Cursor query(Uri uri, String[] projection, String selection,
                              String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public final String getType(Uri uri) {
        return null;
    }

    @Override
    public final Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public final int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public final int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
