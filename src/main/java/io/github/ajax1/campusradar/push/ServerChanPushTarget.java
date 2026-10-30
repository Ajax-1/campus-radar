package io.github.ajax1.campusradar.push;

import io.github.ajax1.campusradar.RadarProperties;
import io.github.ajax1.campusradar.collect.Json;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Server酱：微信扫码登录 sct.ftqq.com 拿 SendKey，消息推到个人微信，不用建群。
 */
@Component
public class ServerChanPushTarget implements PushTarget {

    static final int MAX_TITLE = 32;

    private final String key;
    private final String baseUrl;

    @Autowired
    public ServerChanPushTarget(RadarProperties properties) {
        this(properties.push().serverchanKey(), "https://sctapi.ftqq.com");
    }

    ServerChanPushTarget(String key, String baseUrl) {
        this.key = key;
        this.baseUrl = baseUrl;
    }

    @Override
    public String name() {
        return "Server酱";
    }

    @Override
    public boolean enabled() {
        return key != null && !key.isBlank();
    }

    @Override
    public void send(String markdown) {
        String text = PushHttp.stripFontTags(markdown);
        String form = "title=" + encode(title(text)) + "&desp=" + encode(text);
        HttpResponse<String> resp = PushHttp.post(baseUrl + "/" + key.trim() + ".send",
                "application/x-www-form-urlencoded", form, "Server酱");
        JsonNode root = Json.parse(resp.body());
        if (resp.statusCode() != 200 || root.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("Server酱推送失败: HTTP " + resp.statusCode() + " " + Json.text(root, "message"));
        }
    }

    /**
     * 第一行去掉 markdown 标记作为标题
     */
    static String title(String markdown) {
        String first = markdown.strip().lines().findFirst().orElse("校招雷达").replace("*", "").replace("#", "").strip();
        return first.length() <= MAX_TITLE ? first : first.substring(0, MAX_TITLE);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
