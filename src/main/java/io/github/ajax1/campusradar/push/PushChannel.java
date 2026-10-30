package io.github.ajax1.campusradar.push;

/**
 * 业务代码用的推送入口，具体发到哪里见 {@link PushTarget}
 */
public interface PushChannel {

    boolean enabled();

    /**
     * 发送一条 markdown 消息，失败抛异常
     */
    void sendMarkdown(String markdown);
}
