package io.github.ajax1.campusradar.schedule;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class EventViewTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final ZoneId NY = ZoneId.of("America/New_York");

    private static ScheduleEvent event(ZonedDateTime start, boolean deadline) {
        return new ScheduleEvent("字节跳动", EventKind.INTERVIEW, "字节跳动 · 面试", start.toInstant(), null, deadline,
                Instant.now());
    }

    @Test
    void beijingOnlyWithoutDisplayZone() {
        ScheduleEvent e = event(ZonedDateTime.of(2026, 10, 28, 19, 0, 0, 0, SH), false);
        assertThat(EventView.format(e, SH, null)).isEqualTo("10月28日 周三 19:00");
        assertThat(EventView.format(e, SH, SH)).isEqualTo("10月28日 周三 19:00");
    }

    @Test
    void localTimeAddedForOverseasUser() {
        // 北京 10 月 28 日 19:00 = 美东同日 07:00（夏令时）
        assertThat(EventView.format(event(ZonedDateTime.of(2026, 10, 28, 19, 0, 0, 0, SH), false), SH, NY))
                .isEqualTo("10月28日 周三 19:00（当地 07:00）");
        // 北京 10 月 28 日 09:00 = 美东前一天 21:00，要带上日期
        assertThat(EventView.format(event(ZonedDateTime.of(2026, 10, 28, 9, 0, 0, 0, SH), false), SH, NY))
                .isEqualTo("10月28日 周三 09:00（当地 10月27日 周二 21:00）");
        assertThat(EventView.format(event(ZonedDateTime.of(2026, 10, 30, 23, 59, 0, 0, SH), true), SH, NY))
                .isEqualTo("10月30日 周五 23:59（当地 11:59） 截止");
    }
}
