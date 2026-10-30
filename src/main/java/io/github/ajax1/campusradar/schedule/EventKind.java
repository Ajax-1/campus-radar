package io.github.ajax1.campusradar.schedule;

import io.github.ajax1.campusradar.application.ApplicationStatus;

public enum EventKind {
    INTERVIEW("面试", ApplicationStatus.INTERVIEW),
    WRITTEN_TEST("笔试", ApplicationStatus.ASSESSMENT),
    ASSESSMENT("测评", ApplicationStatus.ASSESSMENT),
    OTHER("日程", null);

    private final String label;
    private final ApplicationStatus impliedStatus;

    EventKind(String label, ApplicationStatus impliedStatus) {
        this.label = label;
        this.impliedStatus = impliedStatus;
    }

    public String label() {
        return label;
    }

    /**
     * 收到这类日程说明投递推进到了哪一步
     */
    public ApplicationStatus impliedStatus() {
        return impliedStatus;
    }

    public static EventKind parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        for (EventKind k : values()) {
            if (k.name().equalsIgnoreCase(v) || k.label.equals(v)) {
                return k;
            }
        }
        return null;
    }
}
