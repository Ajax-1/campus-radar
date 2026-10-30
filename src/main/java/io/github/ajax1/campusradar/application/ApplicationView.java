package io.github.ajax1.campusradar.application;

import java.time.Instant;
import java.util.List;

/**
 * @param timeline 状态变化和备注，按时间先后
 */
public record ApplicationView(long id, String company, String title, String status, String source, Long jobId,
                              String applyUrl, String note, Instant createdAt, Instant updatedAt,
                              List<Entry> timeline) {

    public record Entry(Instant at, String status, String note) {
    }

    static ApplicationView of(Application a, List<ApplicationLog> logs) {
        return new ApplicationView(a.getId(), a.getCompany(), a.getTitle(), a.getStatus().label(), a.getSource().name(),
                a.getJobId(), a.getApplyUrl(), a.getNote(), a.getCreatedAt(), a.getUpdatedAt(),
                logs.stream().map(l -> new Entry(l.getAt(), l.getStatus() == null ? null : l.getStatus().label(),
                        l.getNote())).toList());
    }
}
