package io.github.ajax1.campusradar.push;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 推送渠道共用的 HTTP 调用。地址里带密钥，所以异常里只写异常类型，不写地址
 */
final class PushHttp {

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private PushHttp() {
    }

    static HttpResponse<String> post(String url, String contentType, String body, String label) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(label + "推送请求失败: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(label + "推送被中断", e);
        }
    }

    /**
     * 企业微信专有的 font 标签，其他渠道会原样显示出来
     */
    static String stripFontTags(String markdown) {
        return markdown.replaceAll("</?font[^>]*>", "");
    }
}
