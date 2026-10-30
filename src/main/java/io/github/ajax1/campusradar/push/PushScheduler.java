package io.github.ajax1.campusradar.push;

import io.github.ajax1.campusradar.collect.CollectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PushScheduler {

    private static final Logger log = LoggerFactory.getLogger(PushScheduler.class);

    private final DailyPushService pushService;
    private final CollectService collectService;

    public PushScheduler(DailyPushService pushService, CollectService collectService) {
        this.pushService = pushService;
        this.collectService = collectService;
    }

    /**
     * 等采集结束再补推，这样推的是最新的岗位
     */
    @Scheduled(fixedDelayString = "PT30M", initialDelayString = "PT5M")
    public void catchUp() {
        if (!collectService.collecting() && pushService.missedToday()) {
            daily();
        }
    }

    @Scheduled(cron = "${radar.push.cron}", zone = "${radar.push.zone}")
    public void daily() {
        try {
            DailyPushService.Outcome outcome = pushService.pushNow();
            log.info("每日推送结束: {}，{} 个岗位", outcome.status(), outcome.matches().size());
        } catch (RuntimeException e) {
            // 档案没填、webhook 失效等，不影响其他定时任务
            log.warn("每日推送失败: {}", e.getMessage());
        }
    }
}
