package io.github.ajax1.campusradar;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 应用配置，对应 application.yml 里的 radar.*
 *
 * @param collect     采集相关配置
 * @param mcp         MCP 访问控制
 * @param feishuSites 使用飞书招聘系统的公司站点，每个站点生成一个采集器
 * @param push        每日推送
 * @param mail        邮箱同步（识别笔试、面试邀请）
 * @param hosted      公开部署的只读模式
 */
@ConfigurationProperties(prefix = "radar")
public record RadarProperties(Collect collect, Mcp mcp, List<FeishuSite> feishuSites, Push push, Mail mail,
                              Hosted hosted) {

    public RadarProperties {
        feishuSites = feishuSites == null ? List.of() : List.copyOf(feishuSites);
        push = push == null ? new Push(null, null, null, "-", "Asia/Shanghai", 5, 2) : push;
        mail = mail == null ? new Mail(null, 993, null, null, "INBOX", 14, "-", "Asia/Shanghai", null) : mail;
        hosted = hosted == null ? new Hosted(false, 60, 3000) : hosted;
    }

    /**
     * 公开部署时打开：MCP 和 REST 只留岗位相关的只读能力，档案、台账、日程、邮箱一律不暴露，并按来源 IP 限流。
     *
     * @param enabled            是否为只读公开模式
     * @param ratePerMinute      单个来源 IP 每分钟的请求上限
     * @param totalRatePerMinute 全局每分钟的请求上限，来源 IP 被伪造时的兜底
     */
    public record Hosted(boolean enabled, int ratePerMinute, int totalRatePerMinute) {
    }

    /**
     * IMAP 只读访问。QQ、163、Gmail 都要用「授权码 / 应用专用密码」，不是登录密码。
     *
     * @param host         如 imap.qq.com、imap.163.com、imap.gmail.com；为空则不同步
     * @param lookbackDays 首次同步往回看几天
     * @param cron         定时同步
     * @param zone         邮件里没写时区时按这个解析时间（国内公司默认北京时间）
     * @param displayZone  人在海外时填当地时区（如 America/New_York），日程会同时显示北京时间和当地时间；为空只显示北京时间
     */
    public record Mail(String host, int port, String username, String password, String folder, int lookbackDays,
                       String cron, String zone, String displayZone) {
        public boolean enabled() {
            return host != null && !host.isBlank() && username != null && !username.isBlank()
                    && password != null && !password.isBlank();
        }
    }

    /**
     * @param wecomWebhook  企业微信群机器人 webhook 地址
     * @param feishuWebhook 飞书群自定义机器人 webhook 地址
     * @param serverchanKey Server酱的 SendKey，消息推到个人微信
     * @param cron          推送时间，默认在每日采集之后
     * @param zone          cron 使用的时区
     * @param topN          每次推送的岗位数
     * @param maxPerCompany 一次推送里同一家公司最多几个岗位
     */
    public record Push(String wecomWebhook, String feishuWebhook, String serverchanKey, String cron, String zone,
                       int topN, int maxPerCompany) {
    }

    /**
     * @param cron              定时采集的 cron 表达式
     * @param zone              cron 使用的时区
     * @param runOnStartup      启动后是否立即跑一轮采集
     * @param requestIntervalMs 同一数据源两次列表请求之间的最小间隔
     * @param detailIntervalMs  逐个拉取详情时的最小间隔
     * @param disabledSources   暂停采集的数据源编码
     * @param whenEmpty         启动时岗位库是空的就立即采集一轮，第一次打开就有数据
     */
    public record Collect(String cron, String zone, boolean runOnStartup, long requestIntervalMs,
                          long detailIntervalMs, List<String> disabledSources, boolean whenEmpty) {
        public Collect {
            disabledSources = disabledSources == null ? List.of() : List.copyOf(disabledSources);
        }
    }

    /**
     * @param source  数据源编码，如 nio
     * @param company 公司名，如 蔚来
     * @param baseUrl 站点根地址，如 https://nio.jobs.feishu.cn
     * @param aliases 公司的其他叫法
     */
    public record FeishuSite(String source, String company, String baseUrl, List<String> aliases) {
    }

    /**
     * @param token 非空时，访问 /mcp 必须带 Authorization: Bearer &lt;token&gt;
     */
    public record Mcp(String token) {
    }
}
