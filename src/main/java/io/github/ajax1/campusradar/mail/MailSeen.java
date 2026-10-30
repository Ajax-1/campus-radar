package io.github.ajax1.campusradar.mail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 处理过的邮件，按 Message-ID 去重；也是「识别结果」的记录，方便排查漏识别和误识别
 */
@Entity
@Table(name = "mail_seen")
public class MailSeen {

    @Id
    @Column(length = 512)
    private String messageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MailFinding.Type type;

    @Column(length = 64)
    private String company;

    @Column(length = 256)
    private String subject;

    private Instant receivedAt;

    @Column(nullable = false)
    private Instant processedAt;

    protected MailSeen() {
    }

    public MailSeen(String messageId, MailFinding.Type type, String company, String subject, Instant receivedAt,
                    Instant processedAt) {
        this.messageId = messageId;
        this.type = type;
        this.company = company;
        this.subject = subject == null || subject.length() <= 256 ? subject : subject.substring(0, 256);
        this.receivedAt = receivedAt;
        this.processedAt = processedAt;
    }

    public String getMessageId() {
        return messageId;
    }

    public MailFinding.Type getType() {
        return type;
    }

    public String getCompany() {
        return company;
    }

    public String getSubject() {
        return subject;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
