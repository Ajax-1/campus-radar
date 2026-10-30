package io.github.ajax1.campusradar.application;

/**
 * 投递进度。顺序即正常推进的先后，邮件解析只会把状态往前推，不会倒退（拒信和撤回除外）。
 */
public enum ApplicationStatus {
    SAVED("想投"),
    APPLIED("已投递"),
    ASSESSMENT("笔试/测评"),
    INTERVIEW("面试中"),
    OFFER("Offer"),
    REJECTED("未通过"),
    WITHDRAWN("已放弃");

    private final String label;

    ApplicationStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isFinal() {
        return this == OFFER || this == REJECTED || this == WITHDRAWN;
    }

    /**
     * 兼容英文枚举名和中文标签
     */
    public static ApplicationStatus parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        for (ApplicationStatus s : values()) {
            if (s.name().equalsIgnoreCase(v) || s.label.equals(v)) {
                return s;
            }
        }
        return switch (v) {
            case "笔试", "测评" -> ASSESSMENT;
            case "面试" -> INTERVIEW;
            case "拒绝", "挂了" -> REJECTED;
            default -> null;
        };
    }
}
