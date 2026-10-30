package io.github.ajax1.campusradar.collect;

import io.github.ajax1.campusradar.RadarProperties;
import io.github.ajax1.campusradar.job.JobPostingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CollectScheduler {

    private static final Logger log = LoggerFactory.getLogger(CollectScheduler.class);

    private final CollectService collectService;
    private final RadarProperties properties;
    private final JobPostingRepository jobs;

    public CollectScheduler(CollectService collectService, RadarProperties properties, JobPostingRepository jobs) {
        this.collectService = collectService;
        this.properties = properties;
        this.jobs = jobs;
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

    private void runAll(String trigger) {
        log.info("{}采集开始，数据源: {}", trigger, collectService.sources());
        List<CollectRun> results = collectService.runAll();
        long bad = results.stream().filter(r -> r.getStatus() != CollectRun.Status.SUCCESS).count();
        log.info("{}采集结束，共 {} 个数据源，异常 {} 个", trigger, results.size(), bad);
    }
}
