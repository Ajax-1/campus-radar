package io.github.ajax1.campusradar.collect;

import io.github.ajax1.campusradar.RadarProperties;
import io.github.ajax1.campusradar.collect.source.FeishuAtsCollector;
import io.github.ajax1.campusradar.snapshot.SnapshotCollector;
import io.github.ajax1.campusradar.snapshot.SnapshotSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 全部采集器：各公司官网的专用采集器是 Spring Bean，飞书招聘站点按配置逐个生成。
 * 配置了快照地址时，每个数据源都改从快照读取，后面的入库、下线和健康检查不变。
 */
@Component
public class CollectorRegistry {

    private final List<JobCollector> all;

    public CollectorRegistry(List<JobCollector> beans, RadarProperties properties, SnapshotSource snapshot) {
        List<JobCollector> list = new ArrayList<>(beans);
        for (RadarProperties.FeishuSite site : properties.feishuSites()) {
            list.add(new FeishuAtsCollector(site.source(), site.company(), site.aliases(), site.baseUrl()));
        }
        Set<String> seen = new HashSet<>();
        for (JobCollector c : list) {
            if (!seen.add(c.source())) {
                throw new IllegalStateException("数据源编码重复: " + c.source());
            }
        }
        this.all = properties.snapshot().enabled()
                ? list.stream().<JobCollector>map(c -> new SnapshotCollector(c, snapshot)).toList()
                : List.copyOf(list);
    }

    public List<JobCollector> all() {
        return all;
    }
}
