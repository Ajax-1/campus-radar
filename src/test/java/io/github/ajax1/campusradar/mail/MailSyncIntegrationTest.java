package io.github.ajax1.campusradar.mail;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import io.github.ajax1.campusradar.application.ApplicationLogRepository;
import io.github.ajax1.campusradar.application.ApplicationRepository;
import io.github.ajax1.campusradar.application.ApplicationService;
import io.github.ajax1.campusradar.application.ApplicationStatus;
import io.github.ajax1.campusradar.application.ApplicationView;
import io.github.ajax1.campusradar.application.Application;
import io.github.ajax1.campusradar.push.PushChannel;
import io.github.ajax1.campusradar.schedule.EventView;
import io.github.ajax1.campusradar.schedule.ScheduleEventRepository;
import io.github.ajax1.campusradar.schedule.ScheduleService;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用 GreenMail 起一个真实的 IMAP 服务器，走完整的「收信 → 识别 → 台账 → 日程 → 推送」链路
 */
@SpringBootTest(properties = {
        "radar.mail.host=127.0.0.1",
        "radar.mail.username=me@example.com",
        "radar.mail.password=secret",
        "radar.mail.lookback-days=30"
})
class MailSyncIntegrationTest {

    /**
     * 随机空闲端口，避免和本机其他 IMAP 服务（比如手动起的 GreenMail）冲突
     */
    static final int IMAP_PORT = freePort();

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(
            new ServerSetup(IMAP_PORT, "127.0.0.1", ServerSetup.PROTOCOL_IMAP))
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("me@example.com", "me@example.com", "secret"))
            .withPerMethodLifecycle(true);

    @DynamicPropertySource
    static void mailPort(DynamicPropertyRegistry registry) {
        registry.add("radar.mail.port", () -> IMAP_PORT);
    }

    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

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
    MailSyncService sync;
    @Autowired
    ApplicationService applications;
    @Autowired
    ApplicationRepository applicationRepository;
    @Autowired
    ApplicationLogRepository logs;
    @Autowired
    ScheduleEventRepository events;
    @Autowired
    ScheduleService schedule;
    @Autowired
    MailSeenRepository seen;
    @Autowired
    CapturingChannel channel;

    @BeforeEach
    void setUp() {
        events.deleteAll();
        logs.deleteAll();
        applicationRepository.deleteAll();
        seen.deleteAll();
        channel.sent.clear();
    }

    @Test
    void interviewInviteBecomesEventAndAdvancesApplication() throws Exception {
        // 用户之前已经投了字节和拼多多
        applications.record("字节跳动", "后端开发工程师", null, null, ApplicationStatus.APPLIED,
                Application.Source.MANUAL, null);
        applications.record("拼多多", "服务端研发", null, null, ApplicationStatus.APPLIED, Application.Source.MANUAL, null);

        LocalDate day = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(3);
        String when = day.getYear() + "年" + day.getMonthValue() + "月" + day.getDayOfMonth() + "日 19:00-20:00";
        deliver(htmlMail("字节跳动校园招聘", "campus@bytedance.com", "【字节跳动】面试邀请-后端开发工程师",
                "<p>同学你好，</p><p>面试时间：<b>" + when + "</b>（北京时间）</p>"
                        + "<p>面试链接：<a href=\"https://vc.feishu.cn/j/123\">https://vc.feishu.cn/j/123</a></p>"));
        deliver(textMail("拼多多招聘", "hr@pddglobalhr.com", "感谢您关注拼多多",
                "很遗憾，您未能进入下一轮面试。"));
        deliver(textMail("牛客网", "news@nowcoder.com", "本周面试技巧直播课", "限时报名立减"));

        MailSyncService.Report report = sync.syncNow();
        assertThat(report.scanned()).isEqualTo(3);
        assertThat(report.processed()).isEqualTo(3);
        assertThat(report.findings()).isEqualTo(2);
        assertThat(report.errors()).isEmpty();

        List<EventView> upcoming = schedule.upcoming(14);
        assertThat(upcoming).hasSize(1);
        EventView e = upcoming.getFirst();
        assertThat(e.title()).isEqualTo("字节跳动 · 面试 · 后端开发工程师");
        assertThat(e.link()).isEqualTo("https://vc.feishu.cn/j/123");
        assertThat(e.localTime()).contains(day.getMonthValue() + "月" + day.getDayOfMonth() + "日").contains("19:00");

        List<ApplicationView> apps = applications.list(null);
        assertThat(apps).extracting(ApplicationView::company, ApplicationView::status)
                .contains(org.assertj.core.groups.Tuple.tuple("字节跳动", "面试中"),
                        org.assertj.core.groups.Tuple.tuple("拼多多", "未通过"));
        ApplicationView bytedance = apps.stream().filter(a -> a.company().equals("字节跳动")).findFirst().orElseThrow();
        assertThat(bytedance.timeline()).extracting(ApplicationView.Entry::status).containsExactly("已投递", "面试中");
        assertThat(bytedance.timeline().getLast().note()).contains("【字节跳动】面试邀请");

        assertThat(channel.sent).hasSize(1);
        assertThat(channel.sent.getFirst()).contains("新增 1 个日程").contains("字节跳动：已投递 → 面试中")
                .contains("拼多多：已投递 → 未通过");

        // 再同步一次：邮件都处理过了，不会重复建日程、不会再推送
        MailSyncService.Report again = sync.syncNow();
        assertThat(again.processed()).isZero();
        assertThat(events.count()).isEqualTo(1);
        assertThat(channel.sent).hasSize(1);
    }

    @Test
    void inviteFromUnknownCompanyStillLandsOnCalendar() throws Exception {
        LocalDate day = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(2);
        deliver(textMail("Recruiting", "noreply@ats.example.com", "Interview schedule",
                "Your interview: " + day + " 10:00 (北京时间)"));
        sync.syncNow();
        assertThat(schedule.upcoming(7)).extracting(EventView::company).containsExactly("未知公司");
    }

    private void deliver(MimeMessage m) {
        greenMail.getUserManager().getUser("me@example.com").deliver(m);
    }

    private static MimeMessage textMail(String fromName, String from, String subject, String text) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new java.util.Properties()));
        m.setFrom(new InternetAddress(from, fromName, "UTF-8"));
        m.setRecipients(Message.RecipientType.TO, "me@example.com");
        m.setSubject(subject, "UTF-8");
        m.setText(text, "UTF-8");
        m.setSentDate(new Date());
        m.saveChanges();
        return m;
    }

    private static MimeMessage htmlMail(String fromName, String from, String subject, String html) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new java.util.Properties()));
        m.setFrom(new InternetAddress(from, fromName, "UTF-8"));
        m.setRecipients(Message.RecipientType.TO, "me@example.com");
        m.setSubject(subject, "UTF-8");
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(html, "text/html; charset=UTF-8");
        MimeMultipart mp = new MimeMultipart("alternative");
        mp.addBodyPart(htmlPart);
        m.setContent(mp);
        m.setSentDate(new Date());
        m.saveChanges();
        return m;
    }
}
