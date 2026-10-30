package io.github.ajax1.campusradar.snapshot;

import io.github.ajax1.campusradar.collect.HttpFetcher;
import io.github.ajax1.campusradar.collect.JobCollector;
import io.github.ajax1.campusradar.collect.RawJob;

import java.util.List;

/**
 * 顶替某个数据源的官网采集器：公司信息不变，岗位改从快照读，完全不请求官网
 */
public class SnapshotCollector implements JobCollector {

    private final JobCollector origin;
    private final SnapshotSource snapshot;

    public SnapshotCollector(JobCollector origin, SnapshotSource snapshot) {
        this.origin = origin;
        this.snapshot = snapshot;
    }

    @Override
    public String source() {
        return origin.source();
    }

    @Override
    public String company() {
        return origin.company();
    }

    @Override
    public List<String> aliases() {
        return origin.aliases();
    }

    @Override
    public List<RawJob> collect(HttpFetcher http) {
        return snapshot.jobs().getOrDefault(source(), List.of());
    }
}
