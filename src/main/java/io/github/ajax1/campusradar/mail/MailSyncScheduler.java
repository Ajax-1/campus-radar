package io.github.ajax1.campusradar.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MailSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(MailSyncScheduler.class);

    private final MailSyncService sync;

    public MailSyncScheduler(MailSyncService sync) {
        this.sync = sync;
    }

    @Scheduled(cron = "${radar.mail.cron}", zone = "${radar.mail.zone}")
    public void run() {
        if (!sync.enabled()) {
            return;
        }
        try {
            sync.syncNow();
        } catch (RuntimeException e) {
            // 密码过期、网络断开等，下一轮再试
            log.warn("定时邮箱同步失败: {}", e.getMessage());
        }
    }
}
