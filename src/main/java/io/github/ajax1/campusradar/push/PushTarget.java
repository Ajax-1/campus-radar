package io.github.ajax1.campusradar.push;

/**
 * 一个具体的推送目的地（企业微信、飞书、Server酱）。业务代码用 {@link PushChannel}，它会发到所有配置了的目的地。
 */
public interface PushTarget {

    String name();

    boolean enabled();

    /**
     * 发送一条 markdown 消息，失败抛异常。异常信息不能带 webhook 地址或密钥
     */
    void send(String markdown);
}
