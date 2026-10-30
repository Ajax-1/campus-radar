package io.github.ajax1.campusradar.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AccessFilterTest {

    @Test
    void tokenProtectsMcpAndApi() throws Exception {
        AccessFilter filter = new AccessFilter("secret");
        assertThat(status(filter, request("/mcp", "127.0.0.1"))).isEqualTo(401);
        assertThat(status(filter, request("/api/jobs", "127.0.0.1"))).isEqualTo(401);

        MockHttpServletRequest withToken = request("/api/jobs", "203.0.113.5");
        withToken.addHeader("Authorization", "Bearer secret");
        assertThat(status(filter, withToken)).isEqualTo(200);

        MockHttpServletRequest wrongToken = request("/mcp", "203.0.113.5");
        wrongToken.addHeader("Authorization", "Bearer nope");
        assertThat(status(filter, wrongToken)).isEqualTo(401);
    }

    @Test
    void withoutTokenEverythingButAdminIsOpen() throws Exception {
        AccessFilter filter = new AccessFilter("");
        assertThat(status(filter, request("/mcp", "203.0.113.5"))).isEqualTo(200);
        assertThat(status(filter, request("/api/jobs", "203.0.113.5"))).isEqualTo(200);
    }

    @Test
    void adminOnlyForDirectLocalRequests() throws Exception {
        AccessFilter filter = new AccessFilter("secret");
        assertThat(status(filter, request("/api/admin/collect", "127.0.0.1"))).isEqualTo(200);
        assertThat(status(filter, request("/api/admin/collect", "203.0.113.5"))).isEqualTo(403);

        // cloudflared 转发进来的请求 remoteAddr 也是 127.0.0.1，但带 CF 头
        MockHttpServletRequest tunneled = request("/api/admin/collect", "127.0.0.1");
        tunneled.addHeader("CF-Connecting-IP", "198.51.100.7");
        assertThat(status(filter, tunneled)).isEqualTo(403);

        // 带了正确令牌也不行
        MockHttpServletRequest proxiedWithToken = request("/api/admin/collect", "127.0.0.1");
        proxiedWithToken.addHeader("X-Forwarded-For", "198.51.100.7");
        proxiedWithToken.addHeader("Authorization", "Bearer secret");
        assertThat(status(filter, proxiedWithToken)).isEqualTo(403);
    }

    @Test
    void tokenlessServerOnlyAnswersLocalHostNames() throws Exception {
        AccessFilter filter = new AccessFilter("");
        MockHttpServletRequest rebinding = request("/api/profile", "127.0.0.1");
        rebinding.setServerName("evil.example");
        assertThat(status(filter, rebinding)).isEqualTo(403);

        MockHttpServletRequest local = request("/api/profile", "127.0.0.1");
        local.setServerName("127.0.0.1");
        assertThat(status(filter, local)).isEqualTo(200);

        // 配了令牌之后隧道域名可以用，靠令牌保护
        MockHttpServletRequest tunneled = request("/api/profile", "127.0.0.1");
        tunneled.setServerName("radar.trycloudflare.com");
        tunneled.addHeader("Authorization", "Bearer secret");
        assertThat(status(new AccessFilter("secret"), tunneled)).isEqualTo(200);
    }

    @Test
    void crossSiteBrowserRequestsAreRejected() throws Exception {
        AccessFilter filter = new AccessFilter("");
        MockHttpServletRequest evil = request("/api/events/1/cancel", "127.0.0.1");
        evil.addHeader("Origin", "https://evil.example");
        assertThat(status(filter, evil)).isEqualTo(403);

        MockHttpServletRequest opaque = request("/mcp", "127.0.0.1");
        opaque.addHeader("Origin", "null");
        assertThat(status(filter, opaque)).isEqualTo(403);

        MockHttpServletRequest extension = request("/api/applications", "127.0.0.1");
        extension.addHeader("Origin", "chrome-extension://abcdefghijklmnop");
        assertThat(status(filter, extension)).isEqualTo(200);

        MockHttpServletRequest page = request("/api/profile", "127.0.0.1");
        page.addHeader("Origin", "http://localhost:8090");
        assertThat(status(filter, page)).isEqualTo(200);
    }

    @Test
    void hostedModeExposesOnlyJobData() throws Exception {
        AccessFilter filter = new AccessFilter("", new RateLimiter(100), new RateLimiter(100));
        assertThat(status(filter, request("/mcp", "203.0.113.5"))).isEqualTo(200);
        assertThat(status(filter, request("/api/jobs", "203.0.113.5"))).isEqualTo(200);
        assertThat(status(filter, request("/api/jobs/42", "203.0.113.5"))).isEqualTo(200);
        assertThat(status(filter, request("/api/sources", "203.0.113.5"))).isEqualTo(200);
        assertThat(status(filter, request("/api/status", "203.0.113.5"))).isEqualTo(200);

        for (String path : new String[]{"/api/profile", "/api/match", "/api/applications", "/api/events", "/api/calendar.ics"}) {
            assertThat(status(filter, request(path, "203.0.113.5"))).as(path).isEqualTo(404);
        }
        assertThat(status(filter, request("/api/admin/collect", "203.0.113.5"))).isEqualTo(403);
    }

    @Test
    void hostedModeLimitsEachClient() throws Exception {
        AccessFilter filter = new AccessFilter("", new RateLimiter(2), new RateLimiter(100));
        assertThat(status(filter, request("/api/jobs", "203.0.113.5"))).isEqualTo(200);
        assertThat(status(filter, request("/api/jobs", "203.0.113.5"))).isEqualTo(200);
        assertThat(status(filter, request("/api/jobs", "203.0.113.5"))).isEqualTo(429);
        assertThat(status(filter, request("/api/jobs", "203.0.113.6"))).isEqualTo(200);

        MockHttpServletRequest behindProxy = request("/api/jobs", "127.0.0.1");
        behindProxy.addHeader("CF-Connecting-IP", "198.51.100.7");
        assertThat(AccessFilter.clientKey(behindProxy)).isEqualTo("198.51.100.7");
    }

    private static MockHttpServletRequest request(String uri, String remoteAddr) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", uri);
        r.setRemoteAddr(remoteAddr);
        return r;
    }

    private static int status(AccessFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
