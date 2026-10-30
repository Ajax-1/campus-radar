package io.github.ajax1.campusradar.mcp;

import io.github.ajax1.campusradar.RadarProperties;
import io.github.ajax1.campusradar.security.AccessFilter;
import io.github.ajax1.campusradar.security.RateLimiter;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpConfig {

    @Bean
    public ToolCallbackProvider jobToolCallbacks(RadarProperties properties, JobTools jobTools,
                                                 ProfileTools profileTools, ApplicationTools applicationTools) {
        if (properties.hosted().enabled()) {
            // 公开部署是多人共用的，档案和台账只属于本机用户，不能出现在这里
            return MethodToolCallbackProvider.builder().toolObjects(jobTools).build();
        }
        return MethodToolCallbackProvider.builder().toolObjects(jobTools, profileTools, applicationTools).build();
    }

    /**
     * 访问规则见 {@link AccessFilter}
     */
    @Bean
    public FilterRegistrationBean<AccessFilter> accessFilter(RadarProperties properties) {
        String token = properties.mcp() == null ? null : properties.mcp().token();
        RadarProperties.Hosted hosted = properties.hosted();
        AccessFilter filter = hosted.enabled()
                ? new AccessFilter(token, new RateLimiter(hosted.ratePerMinute()), new RateLimiter(hosted.totalRatePerMinute()))
                : new AccessFilter(token);
        FilterRegistrationBean<AccessFilter> bean = new FilterRegistrationBean<>(filter);
        bean.addUrlPatterns("/mcp", "/mcp/*", "/api/*");
        return bean;
    }
}
