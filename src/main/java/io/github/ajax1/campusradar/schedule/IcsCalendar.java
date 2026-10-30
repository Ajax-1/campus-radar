package io.github.ajax1.campusradar.schedule;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 生成 iCalendar（RFC 5545）订阅内容。苹果日历、Google 日历、Outlook 都能订阅这个地址，日程会自动同步到手机。
 * 每个日程带两个提醒：提前 1 天和提前 1 小时。
 */
public final class IcsCalendar {

    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private IcsCalendar() {
    }

    public static String render(List<ScheduleEvent> events, Instant now) {
        StringBuilder sb = new StringBuilder();
        line(sb, "BEGIN:VCALENDAR");
        line(sb, "VERSION:2.0");
        line(sb, "PRODID:-//campus-radar//schedule//ZH");
        line(sb, "CALSCALE:GREGORIAN");
        line(sb, "METHOD:PUBLISH");
        line(sb, "X-WR-CALNAME:校招日程");
        line(sb, "X-WR-TIMEZONE:Asia/Shanghai");
        for (ScheduleEvent e : events) {
            Instant end = e.getEndAt() != null ? e.getEndAt()
                    : e.isDeadline() ? e.getStartAt() : e.getStartAt().plus(Duration.ofHours(1));
            String summary = (e.isDeadline() ? "【截止】" : "【" + e.getKind().label() + "】") + e.getTitle();
            StringBuilder desc = new StringBuilder();
            if (e.getLink() != null) {
                desc.append("链接：").append(e.getLink()).append("\n");
            }
            if (e.getSourceSubject() != null) {
                desc.append("来自邮件：").append(e.getSourceSubject()).append("\n");
            }
            desc.append("由 campus-radar 生成");

            line(sb, "BEGIN:VEVENT");
            line(sb, "UID:event-" + e.getId() + "@campus-radar");
            line(sb, "DTSTAMP:" + UTC.format(now));
            line(sb, "DTSTART:" + UTC.format(e.getStartAt()));
            line(sb, "DTEND:" + UTC.format(end));
            line(sb, "SUMMARY:" + escape(summary));
            line(sb, "DESCRIPTION:" + escape(desc.toString()));
            if (e.getLocation() != null) {
                line(sb, "LOCATION:" + escape(e.getLocation()));
            }
            if (e.getLink() != null) {
                line(sb, "URL:" + e.getLink());
            }
            for (String trigger : new String[]{"-P1D", "-PT1H"}) {
                line(sb, "BEGIN:VALARM");
                line(sb, "ACTION:DISPLAY");
                line(sb, "DESCRIPTION:" + escape(summary));
                line(sb, "TRIGGER:" + trigger);
                line(sb, "END:VALARM");
            }
            line(sb, "END:VEVENT");
        }
        line(sb, "END:VCALENDAR");
        return sb.toString();
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n");
    }

    /**
     * 每行不超过 75 字节，超出的折行（续行以空格开头），且不能把一个汉字的 UTF-8 字节拆开
     */
    static void line(StringBuilder sb, String content) {
        int bytes = 0;
        int lineLimit = 75;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < content.length(); ) {
            int cp = content.codePointAt(i);
            int len = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + len > lineLimit) {
                sb.append(current).append("\r\n ");
                current.setLength(0);
                bytes = 1;
                lineLimit = 75;
            }
            current.appendCodePoint(cp);
            bytes += len;
            i += Character.charCount(cp);
        }
        sb.append(current).append("\r\n");
    }
}
