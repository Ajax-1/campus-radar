package io.github.ajax1.campusradar.schedule;

import io.github.ajax1.campusradar.push.PushChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 日程提醒：开始前 24 小时内提醒一次，开始前 1 小时内再提醒一次。每 5 分钟检查一次，多个日程合并成一条消息。
 * 推送失败不标记已提醒，下一轮会重试。
 */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    private final ScheduleEventRepository events;
    private final PushChannel push;
    private final ScheduleService schedule;

    public ReminderService(ScheduleEventRepository events, PushChannel push, ScheduleService schedule) {
        this.events = events;
        this.push = push;
        this.schedule = schedule;
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    public void scheduled() {
        try {
            remind(Instant.now());
        } catch (RuntimeException e) {
            log.warn("日程提醒失败: {}", e.getMessage());
        }
    }

    /**
     * @return 这次提醒了几个日程
     */
    @Transactional
    public int remind(Instant now) {
        if (!push.enabled()) {
            return 0;
        }
        List<ScheduleEvent> due = events.findByCancelledFalseAndStartAtBetweenOrderByStartAtAsc(now,
                now.plus(Duration.ofHours(24)));
        List<ScheduleEvent> hour = new ArrayList<>();
        List<ScheduleEvent> day = new ArrayList<>();
        for (ScheduleEvent e : due) {
            boolean withinHour = !e.getStartAt().isAfter(now.plus(Duration.ofHours(1)));
            if (withinHour && !e.isRemindedHourBefore()) {
                hour.add(e);
            } else if (!withinHour && !e.isRemindedDayBefore()) {
                day.add(e);
            }
        }
        if (hour.isEmpty() && day.isEmpty()) {
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        if (!hour.isEmpty()) {
            sb.append("**<font color=\"warning\">1 小时内</font>**\n");
            hour.forEach(e -> append(sb, e));
        }
        if (!day.isEmpty()) {
            sb.append(hour.isEmpty() ? "" : "\n").append("**24 小时内的日程**\n");
            day.forEach(e -> append(sb, e));
        }
        push.sendMarkdown(sb.toString().trim());
        // 1 小时提醒发过了，就不再补发 1 天提醒
        hour.forEach(e -> {
            e.setRemindedHourBefore(true);
            e.setRemindedDayBefore(true);
        });
        day.forEach(e -> e.setRemindedDayBefore(true));
        return hour.size() + day.size();
    }

    private void append(StringBuilder sb, ScheduleEvent e) {
        sb.append("> ").append(schedule.format(e)).append(" ").append(e.getTitle());
        if (e.getLink() != null) {
            sb.append(" [").append(e.isDeadline() ? "去完成" : "进入").append("](").append(e.getLink()).append(")");
        }
        if (e.getLocation() != null) {
            sb.append("\n> <font color=\"comment\">地点：").append(e.getLocation()).append("</font>");
        }
        sb.append("\n");
    }
}
