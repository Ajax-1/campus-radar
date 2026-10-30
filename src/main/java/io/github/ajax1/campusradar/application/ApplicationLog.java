package io.github.ajax1.campusradar.application;

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
 * 投递的时间线：每次状态变化或补充备注记一条
 */
@Entity
@Table(name = "application_log", indexes = @Index(name = "idx_log_app", columnList = "applicationId,at"))
public class ApplicationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long applicationId;

    @Column(nullable = false)
    private Instant at;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private ApplicationStatus status;

    /**
     * 这条记录从哪来：手动、插件、邮件主题等
     */
    @Column(length = 500)
    private String note;

    protected ApplicationLog() {
    }

    public ApplicationLog(Long applicationId, Instant at, ApplicationStatus status, String note) {
        this.applicationId = applicationId;
        this.at = at;
        this.status = status;
        this.note = note == null || note.length() <= 500 ? note : note.substring(0, 500);
    }

    public Instant getAt() {
        return at;
    }

    public ApplicationStatus getStatus() {
        return status;
    }

    public String getNote() {
        return note;
    }
}
