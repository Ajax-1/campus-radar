package io.github.ajax1.campusradar.schedule;

import io.github.ajax1.campusradar.application.ApplicationLogRepository;
import io.github.ajax1.campusradar.application.ApplicationRepository;
import io.github.ajax1.campusradar.application.ApplicationService;
import io.github.ajax1.campusradar.application.ApplicationView;
import io.github.ajax1.campusradar.push.PushChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ScheduleAndReminderTest {

    static class CapturingChannel implements PushChannel {
        final List<String> sent = new ArrayList<>();

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public void sendMarkdown(String markdown) {
            sent.add(markdown);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        CapturingChannel capturingChannel() {
            return new CapturingChannel();
        }
    }

    @Autowired
    ScheduleService schedule;
    @Autowired
    ReminderService reminders;
    @Autowired
    ScheduleEventRepository events;
    @Autowired
    ApplicationService applications;
    @Autowired
    ApplicationRepository applicationRepository;
    @Autowired
    ApplicationLogRepository logs;
    @Autowired
    CapturingChannel channel;

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");

    @BeforeEach
    void setUp() {
        events.deleteAll();
        logs.deleteAll();
        applicationRepository.deleteAll();
        channel.sent.clear();
    }

    private static String inMinutes(Instant now, long minutes) {
        return F.format(now.plus(Duration.ofMinutes(minutes)).atZone(SH));
    }

    @Test
    void manualEventAdvancesApplicationAndIsReminderOnceEach() {
        Instant now = Instant.now();
        schedule.add("字节", EventKind.INTERVIEW, inMinutes(now, 40), null, 60, "https://vc.feishu.cn/j/1", null);
        schedule.add("美团", EventKind.WRITTEN_TEST, inMinutes(now, 10 * 60), null, 120, null, "线上");

        ApplicationView app = applications.list(null).stream().filter(a -> a.company().equals("字节跳动"))
                .findFirst().orElseThrow();
        assertThat(app.status()).isEqualTo("面试中");

        assertThat(reminders.remind(now)).isEqualTo(2);
        assertThat(channel.sent).hasSize(1);
        assertThat(channel.sent.getFirst()).contains("1 小时内").contains("字节跳动 · 面试").contains("[进入](https://vc.feishu.cn/j/1)")
                .contains("24 小时内的日程").contains("美团 · 笔试").contains("地点：线上");

        // 同一时刻再检查：都已提醒过
        assertThat(reminders.remind(now)).isZero();
        // 美团笔试开始前 50 分钟：补 1 小时提醒
        assertThat(reminders.remind(now.plus(Duration.ofMinutes(10 * 60 - 50)))).isEqualTo(1);
        assertThat(channel.sent.getLast()).contains("美团 · 笔试").doesNotContain("字节跳动");
    }

    @Test
    void rejectsBadTime() {
        assertThatThrownBy(() -> schedule.add("字节", EventKind.INTERVIEW, "明天晚上", null, 60, null, null))
                .hasMessageContaining("yyyy-MM-dd HH:mm");
    }

    @Test
    void icsFeedIsValidAndFolded() {
        Instant now = Instant.now();
        schedule.add("字节跳动", EventKind.INTERVIEW, inMinutes(now, 24 * 60), null, 60,
                "https://vc.feishu.cn/j/123456789?from=mail;x=1", "北京市海淀区北三环西路甲18号中鼎大厦，A座");
        String ics = IcsCalendar.render(events.findAll(), now);

        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\n").endsWith("END:VCALENDAR\r\n")
                .contains("SUMMARY:【面试】字节跳动 · 面试")
                .contains("TRIGGER:-P1D").contains("TRIGGER:-PT1H");
        for (String line : ics.split("\r\n")) {
            assertThat(line.getBytes(StandardCharsets.UTF_8).length).as(line).isLessThanOrEqualTo(75);
        }
        // 折行后拼回去，内容不丢
        String unfolded = ics.replace("\r\n ", "");
        // 全角逗号不需要转义；半角分号要转义
        assertThat(unfolded).contains("LOCATION:北京市海淀区北三环西路甲18号中鼎大厦，A座")
                .contains("URL:https://vc.feishu.cn/j/123456789?from=mail;x=1")
                .contains("链接：https://vc.feishu.cn/j/123456789?from=mail\\;x=1");
    }
}
