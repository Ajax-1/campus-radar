package io.github.ajax1.campusradar.api;

import io.github.ajax1.campusradar.application.ApplicationService;
import io.github.ajax1.campusradar.application.ApplicationStatus;
import io.github.ajax1.campusradar.application.ApplicationView;
import io.github.ajax1.campusradar.application.Application;
import io.github.ajax1.campusradar.schedule.EventKind;
import io.github.ajax1.campusradar.schedule.EventView;
import io.github.ajax1.campusradar.schedule.IcsCalendar;
import io.github.ajax1.campusradar.schedule.ScheduleEventRepository;
import io.github.ajax1.campusradar.schedule.ScheduleService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 投递台账、日程、日历订阅
 */
@RestController
@RequestMapping("/api")
public class ScheduleController {

    private final ApplicationService applications;
    private final ScheduleService schedule;
    private final ScheduleEventRepository events;

    public ScheduleController(ApplicationService applications, ScheduleService schedule, ScheduleEventRepository events) {
        this.applications = applications;
        this.schedule = schedule;
        this.events = events;
    }

    public record RecordRequest(String company, String title, Long jobId, String applyUrl, String status, String note,
                                String source) {
    }

    public record UpdateRequest(String status, String note) {
    }

    public record EventRequest(String company, String kind, String start, String timezone, Integer durationMinutes,
                               String link, String location) {
    }

    @GetMapping("/applications")
    public List<ApplicationView> list(@RequestParam(required = false) String status) {
        return applications.list(ApplicationStatus.parse(status));
    }

    /**
     * 浏览器插件在用户点击「已投递」后调用；source 传 EXTENSION
     */
    @PostMapping("/applications")
    public ApplicationView record(@RequestBody RecordRequest r) {
        Application.Source source = "EXTENSION".equalsIgnoreCase(r.source()) ? Application.Source.EXTENSION
                : Application.Source.MANUAL;
        return applications.record(r.company(), r.title(), r.jobId(), r.applyUrl(), ApplicationStatus.parse(r.status()),
                source, r.note());
    }

    @PutMapping("/applications/{id}")
    public ApplicationView update(@PathVariable long id, @RequestBody UpdateRequest r) {
        return applications.update(id, ApplicationStatus.parse(r.status()), r.note());
    }

    @GetMapping("/events")
    public List<EventView> events(@RequestParam(defaultValue = "14") int days) {
        return schedule.upcoming(days);
    }

    @PostMapping("/events")
    public EventView addEvent(@RequestBody EventRequest r) {
        return schedule.add(r.company(), EventKind.parse(r.kind()), r.start(), r.timezone(), r.durationMinutes(),
                r.link(), r.location());
    }

    @PostMapping("/events/{id}/cancel")
    public EventView cancel(@PathVariable long id) {
        return schedule.cancel(id);
    }

    /**
     * 日历订阅地址。日历应用不能带请求头，配置了令牌时用 ?token= 传（见 AccessFilter）
     */
    @GetMapping(value = "/calendar.ics", produces = "text/calendar;charset=UTF-8")
    public ResponseEntity<String> calendar() {
        String body = IcsCalendar.render(events.findByCancelledFalseAndStartAtAfterOrderByStartAtAsc(
                Instant.now().minusSeconds(30L * 24 * 3600)), Instant.now());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/calendar;charset=UTF-8")).body(body);
    }
}
