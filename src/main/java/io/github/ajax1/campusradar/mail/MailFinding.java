package io.github.ajax1.campusradar.mail;

import java.time.Instant;
import java.util.List;

/**
 * 从一封邮件里识别出的求职信息
 *
 * @param company 识别不出时为空
 * @param slots   日程时间；面试给了多个候选时间时有多个
 */
public record MailFinding(Type type, String company, List<Slot> slots, String link, String location) {

    public enum Type {
        INTERVIEW, WRITTEN_TEST, ASSESSMENT, OFFER, REJECTION,
        /**
         * 和求职进度无关
         */
        NONE
    }

    /**
     * @param end      可能为空
     * @param deadline true 表示这是截止时间而不是开始时间
     */
    public record Slot(Instant start, Instant end, boolean deadline) {
    }

    public static MailFinding none() {
        return new MailFinding(Type.NONE, null, List.of(), null, null);
    }
}
