package io.github.ajax1.campusradar.snapshot;

import io.github.ajax1.campusradar.RadarProperties;
import io.github.ajax1.campusradar.collect.CollectRun;
import io.github.ajax1.campusradar.collect.CollectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 定时任务入口：配置了 radar.snapshot.export-path 时采集一轮、写出快照、退出，不继续提供服务
 */
@Component
public class SnapshotExportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SnapshotExportRunner.class);

    private final RadarProperties properties;
    private final CollectService collect;
    private final SnapshotExporter exporter;
    private final ConfigurableApplicationContext context;

    public SnapshotExportRunner(RadarProperties properties, CollectService collect, SnapshotExporter exporter,
                                ConfigurableApplicationContext context) {
        this.properties = properties;
        this.collect = collect;
        this.exporter = exporter;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        String path = properties.snapshot().exportPath();
        if (path == null || path.isBlank()) {
            return;
        }
        List<CollectRun> runs = collect.runAll();
        long failed = runs.stream().filter(r -> r.getStatus() == CollectRun.Status.FAILED).count();
        int exported = exporter.export(Path.of(path));
        log.info("快照已写入 {}：{} 个岗位，{} 个数据源里 {} 个失败", path, exported, runs.size(), failed);
        int code = exported == 0 ? 1 : 0;
        System.exit(SpringApplication.exit(context, () -> code));
    }
}
