package io.github.ajax1.campusradar.mail;

import io.github.ajax1.campusradar.RadarProperties;
import io.github.ajax1.campusradar.push.PushChannel;
import io.github.ajax1.campusradar.schedule.EventView;
import jakarta.mail.Address;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;

/**
 * IMAP 只读同步：拉最近几天的邮件，交给 {@link MailProcessor} 识别，有变化时推送一条汇总。
 * 只读打开收件箱，不标记已读、不移动、不删除。
 */
@Service
public class MailSyncService {

    private static final Logger log = LoggerFactory.getLogger(MailSyncService.class);

    /**
     * @param scanned  这次看过的邮件数（含之前处理过的）
     * @param processed 新处理的邮件数
     * @param findings 识别出的求职邮件（不含无关邮件）
     */
    public record Report(int scanned, int processed, int findings, List<EventView> newEvents, List<String> changes,
                         List<String> errors) {
    }

    private final MailProcessor processor;
    private final PushChannel push;
    private final RadarProperties.Mail config;
    private final ReentrantLock lock = new ReentrantLock();

    public MailSyncService(MailProcessor processor, PushChannel push, RadarProperties properties) {
        this.processor = processor;
        this.push = push;
        this.config = properties.mail();
    }

    public boolean enabled() {
        return config.enabled();
    }

    public Report syncNow() {
        if (!config.enabled()) {
            throw new IllegalStateException("没有配置邮箱（RADAR_MAIL_HOST / RADAR_MAIL_USERNAME / RADAR_MAIL_PASSWORD）");
        }
        if (!lock.tryLock()) {
            throw new IllegalStateException("邮箱正在同步中");
        }
        try {
            Report report = fetchAndProcess();
            notifyChanges(report);
            return report;
        } finally {
            lock.unlock();
        }
    }

    private Report fetchAndProcess() {
        List<EventView> newEvents = new ArrayList<>();
        List<String> changes = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int scanned = 0;
        int processed = 0;
        int findings = 0;
        Store store = null;
        Folder folder = null;
        try {
            store = connect();
            folder = store.getFolder(config.folder());
            folder.open(Folder.READ_ONLY);
            Date since = Date.from(Instant.now().minus(Duration.ofDays(Math.max(1, config.lookbackDays()))));
            Message[] messages = folder.search(new ReceivedDateTerm(ComparisonTerm.GE, since));
            for (Message msg : messages) {
                scanned++;
                try {
                    MailMessage m = toMailMessage(msg);
                    if (processor.alreadySeen(m.messageId())) {
                        continue;
                    }
                    MailProcessor.Result r = processor.process(m);
                    processed++;
                    if (r.finding().type() != MailFinding.Type.NONE) {
                        findings++;
                    }
                    newEvents.addAll(r.newEvents());
                    changes.addAll(r.changes());
                } catch (RuntimeException | MessagingException | IOException e) {
                    // 单封邮件解析失败不影响其他邮件；只记主题，不记正文
                    errors.add(safeSubject(msg) + "：" + e.getClass().getSimpleName());
                    log.warn("处理邮件失败: {}", safeSubject(msg), e);
                }
            }
        } catch (MessagingException e) {
            throw new IllegalStateException("连接邮箱失败：" + e.getMessage(), e);
        } finally {
            closeQuietly(folder, store);
        }
        log.info("邮箱同步：看了 {} 封，新处理 {} 封，识别出 {} 封求职邮件，新增 {} 个日程", scanned, processed, findings,
                newEvents.size());
        return new Report(scanned, processed, findings, newEvents, changes, errors);
    }

    private Store connect() throws MessagingException {
        // 993 是 IMAPS；其他端口（如本地测试服务器）用明文 IMAP
        String protocol = config.port() == 993 ? "imaps" : "imap";
        Properties props = new Properties();
        props.put("mail.store.protocol", protocol);
        props.put("mail." + protocol + ".connectiontimeout", "15000");
        props.put("mail." + protocol + ".timeout", "30000");
        Store store = Session.getInstance(props).getStore(protocol);
        store.connect(config.host(), config.port(), config.username(), config.password());
        return store;
    }

    static MailMessage toMailMessage(Message msg) throws MessagingException, IOException {
        String[] ids = msg.getHeader("Message-ID");
        Address[] from = msg.getFrom();
        String fromName = null;
        String fromAddress = null;
        if (from != null && from.length > 0 && from[0] instanceof InternetAddress ia) {
            fromName = ia.getPersonal();
            fromAddress = ia.getAddress();
        }
        Instant received = msg.getReceivedDate() != null ? msg.getReceivedDate().toInstant()
                : msg.getSentDate() != null ? msg.getSentDate().toInstant() : Instant.now();
        String subject = msg.getSubject();
        String id = ids != null && ids.length > 0 ? ids[0]
                : "sha256:" + sha256(fromAddress + "|" + subject + "|" + received.toEpochMilli());
        return new MailMessage(id, fromName, fromAddress, subject, MailText.of(msg), received);
    }

    /**
     * 有新日程或状态变化时推一条汇总，没有就不打扰
     */
    private void notifyChanges(Report r) {
        if (!push.enabled() || (r.newEvents().isEmpty() && r.changes().isEmpty())) {
            return;
        }
        StringBuilder sb = new StringBuilder("**邮箱同步：");
        sb.append(r.newEvents().isEmpty() ? "投递进度有更新" : "新增 " + r.newEvents().size() + " 个日程").append("**\n");
        for (EventView e : r.newEvents()) {
            sb.append("> ").append(e.localTime()).append(" ").append(e.title());
            if (e.link() != null) {
                sb.append(" [链接](").append(e.link()).append(")");
            }
            sb.append("\n");
        }
        for (String c : r.changes()) {
            sb.append("> <font color=\"comment\">").append(c).append("</font>\n");
        }
        sb.append("\n<font color=\"comment\">已加入日历订阅，开始前 1 天和 1 小时会再提醒。</font>");
        try {
            push.sendMarkdown(sb.toString());
        } catch (RuntimeException e) {
            log.warn("邮箱同步结果推送失败: {}", e.getMessage());
        }
    }

    private static String safeSubject(Message msg) {
        try {
            return msg.getSubject();
        } catch (MessagingException e) {
            return "(无法读取主题)";
        }
    }

    private static void closeQuietly(Folder folder, Store store) {
        try {
            if (folder != null && folder.isOpen()) {
                folder.close(false);
            }
        } catch (MessagingException ignored) {
            // 关闭失败不影响结果
        }
        try {
            if (store != null) {
                store.close();
            }
        } catch (MessagingException ignored) {
            // 关闭失败不影响结果
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
