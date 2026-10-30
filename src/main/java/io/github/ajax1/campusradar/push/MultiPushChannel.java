package io.github.ajax1.campusradar.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 发到所有配置了的目的地。一个失败不影响其他的；全部失败才抛异常，调用方据此判断这次推送没有送达。
 */
@Component
public class MultiPushChannel implements PushChannel {

    private static final Logger log = LoggerFactory.getLogger(MultiPushChannel.class);

    private final List<PushTarget> targets;

    public MultiPushChannel(List<PushTarget> targets) {
        this.targets = List.copyOf(targets);
    }

    @Override
    public boolean enabled() {
        return targets.stream().anyMatch(PushTarget::enabled);
    }

    @Override
    public void sendMarkdown(String markdown) {
        List<PushTarget> active = targets.stream().filter(PushTarget::enabled).toList();
        if (active.isEmpty()) {
            throw new IllegalStateException("没有配置推送渠道（RADAR_SERVERCHAN_KEY、RADAR_WECOM_WEBHOOK 或 RADAR_FEISHU_WEBHOOK）");
        }
        List<String> failures = new ArrayList<>();
        for (PushTarget target : active) {
            try {
                target.send(markdown);
            } catch (RuntimeException e) {
                log.warn("{} 推送失败: {}", target.name(), e.getMessage());
                failures.add(e.getMessage());
            }
        }
        if (failures.size() == active.size()) {
            throw new IllegalStateException(String.join("；", failures));
        }
    }
}
