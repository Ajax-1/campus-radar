package io.github.ajax1.campusradar.push;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PushTargetsTest {

    private HttpServer server;
    private final AtomicReference<String> received = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private volatile String reply = "{\"code\":0}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void serverChanPostsTitleAndBodyWithoutWeComTags() {
        new ServerChanPushTarget("SCT123", base()).send("**校招雷达 · 今日推荐**\n<font color=\"comment\">上海</font>");

        assertThat(path.get()).isEqualTo("/SCT123.send");
        String form = URLDecoder.decode(received.get(), StandardCharsets.UTF_8);
        assertThat(form).contains("title=校招雷达 · 今日推荐").contains("上海").doesNotContain("<font");
    }

    @Test
    void serverChanFailureDoesNotLeakTheKey() {
        reply = "{\"code\":40001,\"message\":\"bad pushkey\"}";
        assertThatThrownBy(() -> new ServerChanPushTarget("SCT123", base()).send("x"))
                .hasMessageContaining("bad pushkey").hasMessageNotContaining("SCT123");
    }

    @Test
    void feishuSendsAMarkdownCard() {
        new FeishuPushTarget(base() + "/hook").send("**标题**\n<font color=\"warning\">提醒</font>");

        assertThat(received.get()).contains("\"msg_type\":\"interactive\"").contains("**标题**").contains("提醒")
                .doesNotContain("font");
    }

    @Test
    void oneFailedTargetDoesNotStopTheOthers() {
        List<String> sent = new ArrayList<>();
        MultiPushChannel channel = new MultiPushChannel(List.of(target("坏", true, null), target("好", true, sent)));

        channel.sendMarkdown("hi");

        assertThat(sent).containsExactly("hi");
    }

    @Test
    void allTargetsFailingIsAnError() {
        MultiPushChannel channel = new MultiPushChannel(List.of(target("坏", true, null)));
        assertThatThrownBy(() -> channel.sendMarkdown("hi")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void disabledTargetsAreSkipped() {
        MultiPushChannel channel = new MultiPushChannel(List.of(target("关", false, new ArrayList<>())));
        assertThat(channel.enabled()).isFalse();
    }

    private static PushTarget target(String name, boolean enabled, List<String> sink) {
        return new PushTarget() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean enabled() {
                return enabled;
            }

            @Override
            public void send(String markdown) {
                if (sink == null) {
                    throw new IllegalStateException("发送失败");
                }
                sink.add(markdown);
            }
        };
    }
}
