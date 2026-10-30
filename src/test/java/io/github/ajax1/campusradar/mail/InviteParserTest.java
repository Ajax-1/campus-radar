package io.github.ajax1.campusradar.mail;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用例按常见的校招邮件写法构造（不是真实邮件）。解析出错时先在这里加一个复现用例。
 */
class InviteParserTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final List<String> KNOWN = List.of("字节跳动", "阿里巴巴", "腾讯", "美团", "百度", "拼多多", "华为");
    private final InviteParser parser = new InviteParser(SH);

    private static Instant at(int y, int mo, int d, int h, int mi, ZoneId zone) {
        return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant();
    }

    private MailFinding parse(String fromName, String fromAddress, String subject, String text, Instant received) {
        return parser.parse(new MailMessage("<id@test>", fromName, fromAddress, subject, text, received), KNOWN);
    }

    private static final Instant OCT25 = at(2026, 10, 25, 10, 0, SH);

    @Test
    void videoInterviewWithRangeWeekdayAndFeishuLink() {
        MailFinding f = parse("字节跳动校园招聘", "campus@bytedance.com", "【字节跳动】面试邀请-后端开发工程师", """
                同学你好：
                感谢你投递字节跳动后端开发工程师岗位，现邀请你参加一面。
                面试时间：2026年10月28日（周三）19:00-20:00（北京时间）
                面试形式：视频面试
                面试链接：https://vc.feishu.cn/j/123456789
                如需改期请回复本邮件。""", OCT25);
        assertThat(f.type()).isEqualTo(MailFinding.Type.INTERVIEW);
        assertThat(f.company()).isEqualTo("字节跳动");
        assertThat(f.slots()).containsExactly(new MailFinding.Slot(
                at(2026, 10, 28, 19, 0, SH), at(2026, 10, 28, 20, 0, SH), false));
        assertThat(f.link()).isEqualTo("https://vc.feishu.cn/j/123456789");
    }

    @Test
    void writtenTestWithChineseHoursAndNowcoderLink() {
        MailFinding f = parse("美团校园招聘", "notice@mokahr.com", "美团2027届校园招聘笔试通知", """
                你好，你已进入笔试环节。
                笔试时间：10月30日 下午3点 - 5点
                请提前15分钟登录 https://www.nowcoder.com/test/abc123 完成设备调试。
                退订：https://mokahr.com/unsubscribe""", OCT25);
        assertThat(f.type()).isEqualTo(MailFinding.Type.WRITTEN_TEST);
        assertThat(f.company()).isEqualTo("美团");
        assertThat(f.slots()).containsExactly(new MailFinding.Slot(
                at(2026, 10, 30, 15, 0, SH), at(2026, 10, 30, 17, 0, SH), false));
        assertThat(f.link()).isEqualTo("https://www.nowcoder.com/test/abc123");
    }

    @Test
    void assessmentDeadline() {
        MailFinding f = parse("阿里巴巴集团招聘", "noreply@service.alibaba.com", "阿里巴巴集团 - 在线测评邀请", """
                请于2026年11月2日 23:59前完成测评，逾期链接失效。
                测评链接：https://talent.alibaba.com/assess/abc""", OCT25);
        assertThat(f.type()).isEqualTo(MailFinding.Type.ASSESSMENT);
        assertThat(f.company()).isEqualTo("阿里巴巴");
        assertThat(f.slots()).containsExactly(new MailFinding.Slot(at(2026, 11, 2, 23, 59, SH), null, true));
    }

    @Test
    void dateOnlyDeadlineMeansEndOfDay() {
        MailFinding f = parse("腾讯招聘", "hr@tencent.com", "腾讯 2027 校招测评邀请", "请在10月31日前完成在线测评。", OCT25);
        assertThat(f.slots()).containsExactly(new MailFinding.Slot(at(2026, 10, 31, 23, 59, SH), null, true));
    }

    @Test
    void rejectionAndOffer() {
        MailFinding reject = parse("拼多多招聘", "hr@pddglobalhr.com", "感谢您关注拼多多",
                "很遗憾，经过综合评估，您未能进入下一轮面试。祝前程似锦。", OCT25);
        assertThat(reject.type()).isEqualTo(MailFinding.Type.REJECTION);
        assertThat(reject.company()).isEqualTo("拼多多");
        assertThat(reject.slots()).isEmpty();

        MailFinding offer = parse("百度招聘", "campus@baidu.com", "百度2027届校园招聘Offer录用通知",
                "恭喜你获得百度的录用，请在11月10日前确认。", OCT25);
        assertThat(offer.type()).isEqualTo(MailFinding.Type.OFFER);
        assertThat(offer.company()).isEqualTo("百度");
    }

    @Test
    void marketingMailIsIgnored() {
        assertThat(parse("牛客网", "news@nowcoder.com", "大厂面试技巧直播课，10月28日 20:00 开讲",
                "名师带你刷真题，报名立减。", OCT25).type()).isEqualTo(MailFinding.Type.NONE);
        assertThat(parse("某平台", "a@b.com", "你的周报", "本周你投递了 3 个岗位", OCT25).type())
                .isEqualTo(MailFinding.Type.NONE);
    }

    @Test
    void englishInviteInEasternTime() {
        MailFinding f = parse("Stripe Recruiting", "recruiting@stripe.com", "Interview Invitation - Software Engineer, New Grad", """
                Hi,
                Your technical interview is scheduled for October 29, 2026 at 2:00 PM ET.
                Join via Zoom: https://zoom.us/j/999888777
                """, OCT25);
        assertThat(f.type()).isEqualTo(MailFinding.Type.INTERVIEW);
        assertThat(f.company()).isEqualTo("Stripe");
        assertThat(f.slots()).containsExactly(new MailFinding.Slot(
                at(2026, 10, 29, 14, 0, ZoneId.of("America/New_York")), null, false));
        assertThat(f.link()).isEqualTo("https://zoom.us/j/999888777");
    }

    @Test
    void yearRollsOverForJanuaryInterviewReceivedInDecember() {
        MailFinding f = parse("字节跳动校园招聘", "campus@bytedance.com", "面试安排", "面试时间：1月5日 上午10:30",
                at(2026, 12, 28, 9, 0, SH));
        assertThat(f.slots()).containsExactly(new MailFinding.Slot(at(2027, 1, 5, 10, 30, SH), null, false));
    }

    @Test
    void multipleCandidateSlotsAndFullWidthDigits() {
        MailFinding f = parse("腾讯招聘", "hr@tencent.com", "腾讯面试时间确认",
                "可选时间：１０月２８日 １４：００ 或 10月29日 上午10点半，请回复确认。", OCT25);
        assertThat(f.slots()).extracting(MailFinding.Slot::start)
                .containsExactly(at(2026, 10, 28, 14, 0, SH), at(2026, 10, 29, 10, 30, SH));
    }

    @Test
    void companyOutsideCatalogFromLedgerOrSender() {
        MailFinding fromLedger = parse("HR", "hr@example.com", "华为技术有限公司-面试通知", "面试时间：11月3日 9:00", OCT25);
        assertThat(fromLedger.company()).isEqualTo("华为");

        MailFinding fromSender = parse("米哈游校园招聘", "campus@mihoyo.com", "面试通知", "面试时间：11月3日 9:00", OCT25);
        assertThat(fromSender.company()).isEqualTo("米哈游");

        MailFinding fromBracket = parse("noreply", "noreply@hotjob.cn", "【网易雷火】笔试通知", "笔试时间：11月1日 19:00", OCT25);
        assertThat(fromBracket.company()).isEqualTo("网易雷火");
    }

    @Test
    void invalidTimesAreSkippedNotFatal() {
        MailFinding f = parse("腾讯招聘", "hr@tencent.com", "面试通知", "面试时间：10月28日 25:00，10月29日 19:00", OCT25);
        assertThat(f.slots()).extracting(MailFinding.Slot::start).containsExactly(at(2026, 10, 29, 19, 0, SH));
    }

    @Test
    void locationLine() {
        MailFinding f = parse("美团校园招聘", "campus@meituan.com", "美团现场面试通知",
                "面试时间：11月6日 14:00\n面试地点：北京市朝阳区望京东路4号恒基伟业大厦B座\n", OCT25);
        assertThat(f.location()).isEqualTo("北京市朝阳区望京东路4号恒基伟业大厦B座");
    }
}
