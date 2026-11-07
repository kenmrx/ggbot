package com.ggbot.app;

import android.net.VpnService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 创建一个真正绑到物理网卡上的 TCP 连接。
 *
 * 关键点：java.net.Socket 的底层 fd 是「懒创建」的——new Socket() 之后、
 * connect() 之前，getFileDescriptor$() 仍然返回 null，此时 VpnService.protect()
 * 拿不到 fd 只能返回 false。所以必须先 bind 一次把 fd 逼出来，再去 protect，
 * 最后才 connect。少这一步，所有上游连接都会被判定为 protect 失败。
 */
public final class Protected {

    private Protected() {
    }

    /**
     * @return 已经 protect 过、可以放心 connect 的 socket；失败返回 null
     */
    public static Socket open(VpnService vpn) {
        Socket s = new Socket();
        try {
            s.bind(new InetSocketAddress(0));
        } catch (IOException e) {
            close(s);
            return null;
        }
        if (vpn == null || !vpn.protect(s)) {
            close(s);
            return null;
        }
        return s;
    }

    public static void close(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
