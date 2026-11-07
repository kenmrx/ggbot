package com.ggbot.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 「宝宝回家」数据层：民政部全国救助寻亲网的弹性抓取。
 *
 * 设计约束（与本项目其它联网代码一致）：
 *   · 公益数据，绝不做二次分发缓存——每次实时拉取，拉不到就回落渠道模式
 *   · 民政接口只对部分网络环境开放（桌面探测不可达），超时视为无数据，绝不阻塞 UI
 *   · 响应体上限 2MB、单条字段截断——公益接口返回的内容同样按不可信输入处理
 *
 * 接口形态（xhfw.mca.gov.cn/facing/listData，POST pageNo/pageSize）：
 *   返回 JSON 数组，常见字段：xb(性别)/xm(姓名)/nl(年龄)/tzdd(走失地点)/ryzt(人员状态)
 * 字段解析按「尽力而为」：抓不到就用空串，界面自会隐藏空行。
 */
public final class BabyHome {

    /** 数据端点。备用端点依次尝试——公益接口不稳定，多路回落。 */
    private static final String[] ENDPOINTS = {
            "https://xhfw.mca.gov.cn/facing/listData",
    };

    /** 官方渠道（回落模式展示用）。 */
    public static final String OFFICIAL_SITE = "https://xhfw.mca.gov.cn/facing/";
    public static final String POLICE_PHONE = "110";

    /** 超时：公益查询不该让用户等太久。 */
    private static final int CONNECT_MS = 5000;
    private static final int READ_MS = 6000;
    private static final int MAX_RESP = 2 * 1024 * 1024;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static final class Child {
        public final String name;
        public final String gender;
        public final String age;
        public final String place;
        public final String date;
        public final String status;
        /** 照片 URL；抓不到为空串，界面用占位图。 */
        public final String photo;

        Child(String name, String gender, String age, String place,
              String date, String status, String photo) {
            this.name = name;
            this.gender = gender;
            this.age = age;
            this.place = place;
            this.date = date;
            this.status = status;
            this.photo = photo;
        }
    }

    /** 拉取结果：children 为空 = 拉取失败/无数据，调用方回落渠道模式。 */
    public static final class Result {
        public final List<Child> children;
        public final String error;

        Result(List<Child> c, String e) {
            children = c;
            error = e;
        }
    }

    private BabyHome() {
    }

    /** 同步拉取；必须在后台线程调用。 */
    public static Result fetch() {
        Exception last = null;
        for (String ep : ENDPOINTS) {
            try {
                return new Result(fetchOne(ep), null);
            } catch (Exception e) {
                last = e;
            }
        }
        return new Result(new ArrayList<>(),
                last == null ? "无数据" : last.getClass().getSimpleName());
    }

    private static List<Child> fetchOne(String endpoint) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) GGBot/1.0");
            byte[] body = "pageNo=1&pageSize=20".getBytes(UTF8);
            c.setFixedLengthStreamingMode(body.length);
            java.io.OutputStream out = c.getOutputStream();
            out.write(body);
            out.flush();
            out.close();
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] rb = new byte[8192];
            int n;
            while ((n = in.read(rb)) > 0) {
                if (buf.size() + n > MAX_RESP) break;
                buf.write(rb, 0, n);
            }
            return parseJson(buf.toString("UTF-8"));
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 极简 JSON 数组解析——只取需要的字段，不为公益接口引入解析库。
     * 内容不可信：所有字段截断到 120 字符，控制字符丢弃（进 UI 的最后一道闸）。
     */
    static List<Child> parseJson(String json) {
        List<Child> out = new ArrayList<>();
        if (json == null) return out;
        int pos = 0;
        while (out.size() < 50) {
            int obj = json.indexOf('{', pos);
            if (obj < 0) break;
            int end = json.indexOf('}', obj);
            if (end < 0) break;
            String body = json.substring(obj + 1, end);
            pos = end + 1;
            String name = pick(body, "xm");
            String gender = pick(body, "xb");
            String age = pick(body, "nl");
            String place = pick(body, "tzdd");
            String date = pick(body, "ssrq");
            if (date.isEmpty()) date = pick(body, "fbrq");
            String status = pick(body, "ryzt");
            String photo = pick(body, "zp");
            // 全空对象是分隔符噪音，跳过
            if (name.isEmpty() && place.isEmpty() && photo.isEmpty()) continue;
            out.add(new Child(name, gender, age, place, date, status, photo));
        }
        return out;
    }

    /** 取 "key":"value" 或 "key":value；值截断 120 字符并去控制字符。 */
    private static String pick(String body, String key) {
        int k = body.indexOf("\"" + key + "\"");
        if (k < 0) return "";
        int colon = body.indexOf(':', k + key.length() + 2);
        if (colon < 0) return "";
        int i = colon + 1;
        while (i < body.length() && body.charAt(i) == ' ') i++;
        if (i >= body.length()) return "";
        StringBuilder sb = new StringBuilder();
        if (body.charAt(i) == '"') {
            i++;
            while (i < body.length() && body.charAt(i) != '"') {
                char ch = body.charAt(i);
                if (ch == '\\' && i + 1 < body.length()) {
                    i++;
                    ch = body.charAt(i);
                }
                if (ch >= 32 && ch != 127) sb.append(ch);
                i++;
            }
        } else {
            while (i < body.length() && body.charAt(i) != ',' && body.charAt(i) != '}') {
                char ch = body.charAt(i);
                if (ch >= 32 && ch != 127) sb.append(ch);
                i++;
            }
        }
        String v = sb.toString().trim();
        // 常见转义还原
        v = v.replace("\\/", "/");
        return v.length() > 120 ? v.substring(0, 120) : v;
    }
}
