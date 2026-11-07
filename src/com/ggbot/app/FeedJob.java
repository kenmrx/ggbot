package com.ggbot.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * 每日规则更新。
 *
 * 早先只在「打开 App 时」检查一次，等于用户不点图标就永远不更新——
 * 装完三个月后规则基本失效，而界面上一切正常，用户根本看不出发生了什么。
 * 去广告的效果天花板由规则新鲜度决定，不由引擎决定，所以这件事不能靠用户想起来。
 *
 * 用 JobScheduler 而不是 AlarmManager 或常驻线程：
 * 系统会挑设备空闲、连着 WiFi 的时候跑，不需要精确时刻，
 * 也不该为了一次下载把机器从睡眠里叫醒。
 */
public class FeedJob extends JobService {

    private static final int JOB_ID = 0x6B07;
    /** 一次全量下载约 3MB，只在非计费网络下做。 */
    private static final long PERIOD_MS = 24L * 3600L * 1000L;
    /** 防重叠：onStopJob 之后系统可能重排并再次 onStartJob，而旧线程还在跑。 */
    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @Override
    public boolean onStartJob(final JobParameters params) {
        // 上一轮还没跑完就又排进来：立刻交还，让系统等条件满足再试，
        // 否则两轮 updateAll 并发，元数据与规则文件互相踩。
        // 契约上「return false」本身就是「没开工、请重排」，不能再叠 jobFinished——
        // 两个重排信号都发，个别 ROM 会把重试节奏翻倍
        if (!RUNNING.compareAndSet(false, true)) {
            return false;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok;
                try {
                    // updateAll 返回成功的源数；全失败（网络断了/源全关了）要如实上报，
                    // 早先一律当成功 jobFinished(false)，系统永远不重试，当天更新静默丢失
                    ok = Feeds.updateAll(FeedJob.this, null) > 0;
                } catch (Throwable ignored) {
                    ok = false;
                } finally {
                    RUNNING.set(false);
                }
                jobFinished(params, !ok);
            }
        }, "ggbot-feed-job");
        t.setDaemon(true);
        t.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // 下载中的线程没法安全取消（取消到一半落盘会写坏规则文件），
        // 让它跑完；RUNNING 保证重叠的 onStartJob 直接让位。
        // 返回 false 表示本轮结束，明天周期任务照常来。
        return false;
    }

    /** 幂等：重复调用只会替换同一个任务，不会堆一串。 */
    public static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (!Config.feedAuto(c)) {
                js.cancel(JOB_ID);
                return;
            }
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, FeedJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setPeriodic(PERIOD_MS)
                    // 重启后仍然有效，否则手机一重启这个任务就没了
                    .setPersisted(true)
                    .build();
            js.schedule(job);
        } catch (Throwable ignored) {
            // 部分 ROM 会禁用 JobScheduler；退化成「打开 App 时检查」也不算坏
        }
    }
}
