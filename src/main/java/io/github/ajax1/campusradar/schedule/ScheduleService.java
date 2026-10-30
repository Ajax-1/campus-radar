package io.github.ajax1.campusradar.schedule;

import io.github.ajax1.campusradar.RadarProperties;
import io.github.ajax1.campusradar.application.Application;
import io.github.ajax1.campusradar.application.ApplicationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

@Service
public class ScheduleService {

    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ScheduleEventRepository events;
    private final ApplicationService applications;
    private final ZoneId zone;
    private final ZoneId local;

    public ScheduleService(ScheduleEventRepository events, ApplicationService applications, RadarProperties properties) {
        this.events = events;
        this.applications = applications;
        this.zone = ZoneId.of(properties.mail().zone());
        String display = properties.mail().displayZone();
        this.local = display == null || display.isBlank() ? null : ZoneId.of(display.trim());
    }

    public ZoneId zone() {
        return zone;
    }

    public EventView view(ScheduleEvent e) {
        return EventView.of(e, zone, local);
    }

    public String format(ScheduleEvent e) {
        return EventView.format(e, zone, local);
    }

    @Transactional(readOnly = true)
    public List<EventView> upcoming(int days) {
        Instant now = Instant.now();
        // 已开始但还没结束的也算（比如正在进行的 2 小时笔试）
        return events.findByCancelledFalseAndStartAtBetweenOrderByStartAtAsc(now.minus(Duration.ofHours(3)),
                        now.plus(Duration.ofDays(Math.max(1, days))))
                .stream()
                .filter(e -> (e.getEndAt() != null ? e.getEndAt() : e.getStartAt().plus(Duration.ofHours(1))).isAfter(now))
                .map(this::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EventView> all() {
        return events.findByCancelledFalseAndStartAtAfterOrderByStartAtAsc(Instant.EPOCH).stream()
                .map(this::view).toList();
    }

    /**
     * 手动添加日程，同时把这家公司的投递推进到对应阶段
     *
     * @param start 「2026-10-28 19:00」格式
     * @param timezone start 的时区，为空按北京时间（配置的 zone）
     */
    @Transactional
    public EventView add(String company, EventKind kind, String start, String timezone, Integer durationMinutes,
                         String link, String location) {
        if (company == null || company.isBlank()) {
            throw new IllegalArgumentException("company 不能为空");
        }
        Instant startAt = parse(start, timezone == null || timezone.isBlank() ? zone : ZoneId.of(timezone.trim()));
        Instant endAt = durationMinutes == null || durationMinutes <= 0 ? null
                : startAt.plus(Duration.ofMinutes(durationMinutes));
        EventKind k = kind == null ? EventKind.OTHER : kind;
        Long applicationId = null;
        String canonical = applications.canonicalCompany(company);
        if (k.impliedStatus() != null) {
            Application app = applications.advanceFromMail(canonical, k.impliedStatus(), "手动添加" + k.label() + "日程");
            applicationId = app.getId();
        }
        ScheduleEvent e = new ScheduleEvent(canonical, k, canonical + " · " + k.label(), startAt, endAt, false,
                Instant.now());
        e.setApplicationId(applicationId);
        e.setLink(blankToNull(link));
        e.setLocation(blankToNull(location));
        return view(events.save(e));
    }

    @Transactional
    public EventView cancel(long id) {
        ScheduleEvent e = events.findById(id).orElseThrow(() -> new IllegalArgumentException("日程不存在: " + id));
        e.setCancelled(true);
        return view(e);
    }

    private static Instant parse(String start, ZoneId in) {
        try {
            return LocalDateTime.parse(start.trim().replace('T', ' '), INPUT).atZone(in).toInstant();
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("开始时间格式应为 yyyy-MM-dd HH:mm，如 2026-10-28 19:00，收到：" + start);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
