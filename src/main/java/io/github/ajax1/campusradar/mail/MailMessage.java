package io.github.ajax1.campusradar.mail;

import java.time.Instant;

/**
 * 解析需要的邮件字段，和 IMAP 解耦，方便测试
 *
 * @param fromName    发件人显示名，如「字节跳动校园招聘」
 * @param fromAddress 发件人地址
 * @param text        正文纯文本（HTML 邮件已去掉标签）
 */
public record MailMessage(String messageId, String fromName, String fromAddress, String subject, String text,
                          Instant receivedAt) {
}
