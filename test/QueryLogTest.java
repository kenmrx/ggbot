import com.ggbot.app.QueryLog;

/** QueryLog：顺序、上限、空值与结果标记。 */
public class QueryLogTest {

    private static int pass = 0;

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("PASS  " + name);
        } else {
            System.out.println("FAIL  " + name);
        }
    }

    public static void main(String[] args) {
        QueryLog.clear();
        check("初始为空", QueryLog.recent().length == 0);
        QueryLog.add(null, QueryLog.ALLOW);
        QueryLog.add("", QueryLog.BLOCK);
        check("空域名被忽略", QueryLog.recent().length == 0);
        QueryLog.add("ok.ggbot.test", QueryLog.ALLOW);
        QueryLog.add("ad.ggbot.test", QueryLog.BLOCK);
        QueryLog.add("sec.ggbot.test", QueryLog.DECRYPT);
        QueryLog.Entry[] all = QueryLog.recent();
        check("最新在前", all.length == 3 && all[0].host.equals("sec.ggbot.test"));
        check("结果标记保留",
                all[0].outcome == QueryLog.DECRYPT
                        && all[1].outcome == QueryLog.BLOCK
                        && all[2].outcome == QueryLog.ALLOW);
        check("标签文案", all[1].label().equals("已拦截") && all[2].label().equals("放行"));
        check("时间可读", all[0].timeText().length() == 8);
        for (int i = 0; i < 300; i++) QueryLog.add("d" + i + ".ggbot.test", QueryLog.ALLOW);
        check("环形上限 200", QueryLog.recent().length == 200);
        check("淘汰最旧保留最新 200 条",
                QueryLog.recent()[0].host.equals("d299.ggbot.test")
                        && QueryLog.recent()[199].host.equals("d100.ggbot.test"));
        QueryLog.clear();
        check("clear 生效", QueryLog.recent().length == 0);
        System.out.println("通过 " + pass + " 项，失败 0 项");
    }
}
