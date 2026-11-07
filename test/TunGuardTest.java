import com.ggbot.app.TunGuard;

/** TunGuard：自愈判定、重启间隔、网卡名识别。 */
public class TunGuardTest {

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
        check("运行中且 tun 掉了要自愈", TunGuard.shouldRestart(true, false, false));
        check("用户停了不自愈", !TunGuard.shouldRestart(true, true, false));
        check("引擎没跑不自愈", !TunGuard.shouldRestart(false, false, false));
        check("tun 还在不动", !TunGuard.shouldRestart(true, false, true));

        check("间隔足够允许重启", TunGuard.gapOk(TunGuard.MIN_RESTART_GAP_MS, 0));
        check("间隔不足不重启", !TunGuard.gapOk(TunGuard.MIN_RESTART_GAP_MS - 1, 0));
        check("间隔从上次重启算起",
                TunGuard.gapOk(TunGuard.MIN_RESTART_GAP_MS * 2, TunGuard.MIN_RESTART_GAP_MS));

        check("tun0 认", TunGuard.isTunName("tun0"));
        check("tun12 认", TunGuard.isTunName("tun12"));
        check("wlan0 不认", !TunGuard.isTunName("wlan0"));
        check("tun 不认", !TunGuard.isTunName("tun"));
        check("tunx 不认", !TunGuard.isTunName("tunx"));
        check("null 不认", !TunGuard.isTunName(null));

        System.out.println("通过 " + pass + " 项");
    }
}
