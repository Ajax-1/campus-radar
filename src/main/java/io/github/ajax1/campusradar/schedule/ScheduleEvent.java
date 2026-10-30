package io.github.ajax1.campusradar.schedule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 笔试、面试、测评等日程。来自邮件解析或手动添加；邮件来的按 (sourceKey) 去重，同一封邮件不会生成两条。
 */
@Entity
@Table(name = "schedule_event", indexes = {
        @Index(name = "idx_event_start", columnList = "cancelled,startAt"),
        @Index(name = "idx_event_source", columnList = "sourceKey")
})
public class ScheduleEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long applicationId;

    @Column(nullable = false, length = 64)
    private String company;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EventKind kind;

    @Column(nullable = false, length = 256)
    private String title;

    @Column(nullable = false)
    private Instant startAt;

    private Instant endAt;

    /**
     * true 表示这是截止时间（如「请在 10 月 28 日 23:59 前完成测评」），不是开始时间
     */
    private boolean deadline;

    @Column(length = 1000)
    private String link;

    @Column(length = 256)
    private String location;

    /**
     * 邮件来源的去重键：Message-ID + 开始时间；手动添加的为空
     */
    @Column(length = 512)
    private String sourceKey;

    @Column(length = 256)
    private String sourceSubject;

    private boolean remindedDayBefore;
    private boolean remindedHourBefore;
    private boolean cancelled;

    @Column(nullable = false)
    private Instant createdAt;

    protected ScheduleEvent() {
    }

    public ScheduleEvent(String company, EventKind kind, String title, Instant startAt, Instant endAt, boolean deadline,
                         Instant createdAt) {
        this.company = company;
        this.kind = kind;
        this.title = title;
        this.startAt = startAt;
        this.endAt = endAt;
        this.deadline = deadline;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(Long applicationId) {
        this.applicationId = applicationId;
    }

    public String getCompany() {
        return company;
    }

    public EventKind getKind() {
        return kind;
    }

    public String getTitle() {
        return title;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public boolean isDeadline() {
        return deadline;
    }

    public String getLink() {
        return link;
    }

    public void setLink(String link) {
        this.link = link;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public void setSourceKey(String sourceKey) {
        this.sourceKey = sourceKey;
    }

    public String getSourceSubject() {
        return sourceSubject;
    }

    public void setSourceSubject(String sourceSubject) {
        this.sourceSubject = sourceSubject;
    }

    public boolean isRemindedDayBefore() {
        return remindedDayBefore;
    }

    public void setRemindedDayBefore(boolean remindedDayBefore) {
        this.remindedDayBefore = remindedDayBefore;
    }

    public boolean isRemindedHourBefore() {
        return remindedHourBefore;
    }

    public void setRemindedHourBefore(boolean remindedHourBefore) {
        this.remindedHourBefore = remindedHourBefore;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
