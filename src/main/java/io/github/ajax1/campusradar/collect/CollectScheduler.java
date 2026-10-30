package io.github.ajax1.campusradar.collect;

import io.github.ajax1.campusradar.RadarProperties;
import io.github.ajax1.campusradar.job.JobPostingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class CollectScheduler {

    private static final Logger log = LoggerFactory.getLogger(CollectScheduler.class);

    private final CollectService collectService;
    private final RadarProperties properties;
    private final JobPostingRepository jobs;
    private final CollectRunRepository runs;

    public CollectScheduler(CollectService collectService, RadarProperties properties, JobPostingRepository jobs,
                            CollectRunRepository runs) {
        this.collectService = collectService;
        this.properties = properties;
        this.jobs = jobs;
        this.runs = runs;
    }

    @Scheduled(cron = "${radar.collect.cron}", zone = "${radar.collect.zone}")
    public void daily() {
        runAll("定时");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        boolean firstRun = properties.collect().whenEmpty() && jobs.count() == 0;
        if (properties.collect().runOnStartup() || firstRun) {
            Thread.ofVirtual().name("collect-startup").start(() -> runAll("启动"));
        }
    }

    /**
     * 这个服务大多跑在个人电脑上，定时任务的时刻可能正合着盖。醒来后发现数据过期就补一轮
     */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT2M")
    public void catchUp() {
        int maxAge = properties.collect().maxAgeHours();
        if (maxAge <= 0) {
            return;
        }
        Instant deadline = Instant.now().minus(Duration.ofHours(maxAge));
        boolean stale = runs.findFirstByOrderByStartedAtDesc().map(r -> r.getStartedAt().isBefore(deadline)).orElse(true);
        if (stale) {
            runAll("补偿");
        }
    }

    private void runAll(String trigger) {
        if (collectService.collecting()) {
            log.info("{}采集跳过：已有采集在进行", trigger);
            return;
        }
        log.info("{}采集开始，数据源: {}", trigger, collectService.sources());
        List<CollectRun> results = collectService.runAll();
        long bad = results.stream().filter(r -> r.getStatus() != CollectRun.Status.SUCCESS).count();
        log.info("{}采集结束，共 {} 个数据源，异常 {} 个", trigger, results.size(), bad);
    }
}
