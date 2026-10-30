package io.github.ajax1.campusradar.schedule;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * @param localTime 给人看的开始时间，如「10月28日 周三 19:00」；配置了当地时区时附带当地时间
 */
public record EventView(long id, String company, String kind, String title, Instant startAt, Instant endAt,
                        boolean deadline, String localTime, String link, String location, Long applicationId,
                        String sourceSubject) {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("M月d日 EEE HH:mm", Locale.CHINA);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA);

    public static EventView of(ScheduleEvent e, ZoneId zone, ZoneId local) {
        return new EventView(e.getId(), e.getCompany(), e.getKind().label(), e.getTitle(), e.getStartAt(),
                e.getEndAt(), e.isDeadline(), format(e, zone, local), e.getLink(), e.getLocation(),
                e.getApplicationId(), e.getSourceSubject());
    }

    /**
     * @param local 当地时区，为空或和 zone 相同时只显示 zone 的时间
     */
    public static String format(ScheduleEvent e, ZoneId zone, ZoneId local) {
        var main = e.getStartAt().atZone(zone);
        String text = FMT.format(main);
        if (local != null && !local.normalized().equals(zone.normalized())) {
            var there = e.getStartAt().atZone(local);
            text += "（当地 " + (there.toLocalDate().equals(main.toLocalDate()) ? TIME.format(there) : FMT.format(there)) + "）";
        }
        return text + (e.isDeadline() ? " 截止" : "");
    }
}
