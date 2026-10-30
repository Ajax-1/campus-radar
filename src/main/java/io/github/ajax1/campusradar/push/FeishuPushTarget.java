package io.github.ajax1.campusradar.push;

import io.github.ajax1.campusradar.RadarProperties;
import io.github.ajax1.campusradar.collect.Json;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * 飞书群自定义机器人：群设置 → 群机器人 → 添加自定义机器人，复制 webhook 地址。用卡片消息才能显示 markdown。
 */
@Component
public class FeishuPushTarget implements PushTarget {

    private final String webhook;

    @Autowired
    public FeishuPushTarget(RadarProperties properties) {
        this(properties.push().feishuWebhook());
    }

    FeishuPushTarget(String webhook) {
        this.webhook = webhook;
    }

    @Override
    public String name() {
        return "飞书";
    }

    @Override
    public boolean enabled() {
        return webhook != null && !webhook.isBlank();
    }

    @Override
    public void send(String markdown) {
        String body = Json.write(Map.of("msg_type", "interactive", "card", Map.of(
                "elements", List.of(Map.of("tag", "markdown", "content", PushHttp.stripFontTags(markdown))))));
        HttpResponse<String> resp = PushHttp.post(webhook, "application/json", body, "飞书");
        JsonNode root = Json.parse(resp.body());
        if (resp.statusCode() != 200 || root.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("飞书推送失败: HTTP " + resp.statusCode() + " "
                    + Json.text(root, "code") + " " + Json.text(root, "msg"));
        }
    }
}
