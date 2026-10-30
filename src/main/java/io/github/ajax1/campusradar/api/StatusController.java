package io.github.ajax1.campusradar.api;

import io.github.ajax1.campusradar.RadarProperties;
import io.github.ajax1.campusradar.collect.CollectService;
import io.github.ajax1.campusradar.collect.SourceStatus;
import io.github.ajax1.campusradar.collect.SourceStatusService;
import io.github.ajax1.campusradar.job.JobPostingRepository;
import io.github.ajax1.campusradar.job.JobStatus;
import io.github.ajax1.campusradar.push.PushChannel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 网页界面启动时读这里，决定显示哪些功能、岗位库是否就绪
 */
@RestController
@RequestMapping("/api")
public class StatusController {

    /**
     * @param hosted          公开只读实例，没有档案、台账、日程
     * @param collecting      采集进行中，岗位数还在增长
     * @param lastCollectedAt 最近一次采集开始的时间，从未采集时为空
     */
    public record Status(String version, boolean hosted, long openJobs, int sources, Instant lastCollectedAt,
                         boolean collecting, List<String> collectingSources, boolean mailEnabled, boolean pushEnabled) {
    }

    private final JobPostingRepository jobs;
    private final CollectService collect;
    private final SourceStatusService sourceStatus;
    private final PushChannel push;
    private final RadarProperties properties;
    private final String version;

    public StatusController(JobPostingRepository jobs, CollectService collect, SourceStatusService sourceStatus,
                            PushChannel push, RadarProperties properties,
                            @Value("${spring.ai.mcp.server.version}") String version) {
        this.jobs = jobs;
        this.collect = collect;
        this.sourceStatus = sourceStatus;
        this.push = push;
        this.properties = properties;
        this.version = version;
    }

    @GetMapping("/status")
    public Status status() {
        List<SourceStatus> sources = sourceStatus.list();
        Instant last = sources.stream().map(SourceStatus::lastRunAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        return new Status(version, properties.hosted().enabled(), jobs.countByStatus(JobStatus.OPEN), sources.size(),
                last, collect.collecting(), collect.collectingSources(), properties.mail().enabled(), push.enabled());
    }
}
