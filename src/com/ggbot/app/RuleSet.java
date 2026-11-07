package com.ggbot.app;

import java.util.Arrays;
import java.util.Collection;

/**
 * 只读域名集合：排序数组 + 二分查找。
 *
 * 订阅源动辄二三十万条，HashSet 每个节点要多付 48 字节左右，整体会到 40MB，
 * 对常驻的 VPN 服务进程太重。排序数组省掉哈希节点，只留字符串本身和 4 字节指针，
 * 同样规模能压到 20MB 上下。查询是 O(log n) 次字符串比较，DNS 每秒几十次查询完全够用。
 */
public final class RuleSet {

    public static final RuleSet EMPTY = new RuleSet(new String[0]);

    private final String[] arr;

    private RuleSet(String[] a) {
        arr = a;
    }

    public static RuleSet of(Collection<String> src) {
        if (src == null || src.isEmpty()) return EMPTY;
        String[] a = src.toArray(new String[0]);
        Arrays.sort(a);
        return new RuleSet(a);
    }

    /** 把多个集合合成一个，用于「内置库 + 订阅源」的并集。 */
    public static RuleSet merge(Collection<String> a, Collection<String> b) {
        boolean ea = a == null || a.isEmpty();
        boolean eb = b == null || b.isEmpty();
        if (ea && eb) return EMPTY;
        if (ea) return of(b);
        if (eb) return of(a);

        int n = a.size();
        int m = b.size();
        String[] out = new String[n + m];
        int i = 0;
        for (String s : a) out[i++] = s;
        for (String s : b) out[i++] = s;
        Arrays.sort(out);

        // 两批数据交集不小（国内源彼此抄来抄去），去重能实打实省内存
        int w = 0;
        for (int k = 0; k < out.length; k++) {
            if (w == 0 || !out[k].equals(out[w - 1])) out[w++] = out[k];
        }
        if (w != out.length) out = Arrays.copyOf(out, w);
        return new RuleSet(out);
    }

    public int size() {
        return arr.length;
    }

    public boolean isEmpty() {
        return arr.length == 0;
    }

    public boolean contains(String s) {
        return s != null && Arrays.binarySearch(arr, s) >= 0;
    }

    /** 精确命中，或任一级后缀命中：a.b.ads.com 命中 ads.com。 */
    public boolean matches(String host) {
        if (arr.length == 0 || host == null || host.isEmpty()) return false;
        if (Arrays.binarySearch(arr, host) >= 0) return true;
        int idx = host.indexOf('.');
        while (idx >= 0) {
            int next = idx + 1;
            if (next >= host.length()) return false;
            if (Arrays.binarySearch(arr, host.substring(next)) >= 0) return true;
            idx = host.indexOf('.', next);
        }
        return false;
    }
}
