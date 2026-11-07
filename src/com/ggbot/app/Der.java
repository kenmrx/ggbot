package com.ggbot.app;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 最小化的 DER 编码器，只覆盖签发 X.509 证书所需的类型。
 *
 * 之所以自己拼 DER 而不是引第三方库：整个 App 保持零依赖，
 * 构建链里没有 Gradle、没有 BouncyCastle，只有 aapt2 + javac + d8。
 */
public final class Der {

    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private Der() {
    }

    public static byte[] tlv(int tag, byte[] value) {
        int len = value.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(len + 6);
        out.write(tag);
        if (len < 0x80) {
            out.write(len);
        } else if (len <= 0xFF) {
            out.write(0x81);
            out.write(len);
        } else if (len <= 0xFFFF) {
            out.write(0x82);
            out.write(len >> 8);
            out.write(len);
        } else {
            out.write(0x83);
            out.write(len >> 16);
            out.write(len >> 8);
            out.write(len);
        }
        out.write(value, 0, len);
        return out.toByteArray();
    }

    public static byte[] seq(byte[]... items) {
        return tlv(0x30, concat(items));
    }

    public static byte[] set(byte[]... items) {
        return tlv(0x31, concat(items));
    }

    public static byte[] integer(BigInteger v) {
        return tlv(0x02, v.toByteArray());
    }

    public static byte[] integer(long v) {
        return integer(BigInteger.valueOf(v));
    }

    public static byte[] bool(boolean v) {
        return tlv(0x01, new byte[]{(byte) (v ? 0xFF : 0x00)});
    }

    public static byte[] bitString(byte[] data, int unusedBits) {
        byte[] v = new byte[data.length + 1];
        v[0] = (byte) unusedBits;
        System.arraycopy(data, 0, v, 1, data.length);
        return tlv(0x03, v);
    }

    public static byte[] octetString(byte[] v) {
        return tlv(0x04, v);
    }

    public static byte[] nul() {
        return new byte[]{0x05, 0x00};
    }

    public static byte[] utf8(String s) {
        return tlv(0x0C, s.getBytes(UTF8));
    }

    public static byte[] ia5(String s) {
        return tlv(0x16, s.getBytes(ASCII));
    }

    /** 上下文专用标签，n 为标签号；constructed 决定用 0xA0 还是 0x80 前缀。 */
    public static byte[] ctx(int n, boolean constructed, byte[] v) {
        return tlv((constructed ? 0xA0 : 0x80) | n, v);
    }

    /** 点分 OID 的 DER 编码（首字节 40*a+b，其余 base128）。 */
    public static byte[] oid(String dotted) {
        String[] p = dotted.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Integer.parseInt(p[0]) * 40 + Integer.parseInt(p[1]));
        for (int i = 2; i < p.length; i++) {
            long v = Long.parseLong(p[i]);
            if (v < 0x80) {
                out.write((int) v);
            } else {
                byte[] tmp = new byte[9];
                int n = 0;
                while (v > 0) {
                    tmp[n++] = (byte) (v & 0x7F);
                    v >>= 7;
                }
                for (int k = n - 1; k >= 0; k--) {
                    out.write(k == 0 ? tmp[k] : (tmp[k] | 0x80));
                }
            }
        }
        return tlv(0x06, out.toByteArray());
    }

    /** UTC 时间（1950-2049 区间），格式 YYMMDDHHMMSSZ。 */
    public static byte[] utc(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(d).getBytes(ASCII));
    }

    public static byte[] concat(byte[]... arr) {
        int total = 0;
        for (byte[] a : arr) total += a.length;
        byte[] out = new byte[total];
        int p = 0;
        for (byte[] a : arr) {
            System.arraycopy(a, 0, out, p, a.length);
            p += a.length;
        }
        return out;
    }
}
