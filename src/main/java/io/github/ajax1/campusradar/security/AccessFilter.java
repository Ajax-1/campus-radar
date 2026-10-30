package io.github.ajax1.campusradar.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 访问控制：
 * <ul>
 *     <li>/api/admin/**：只允许直连的本机请求。经 cloudflared、nginx 等转发进来的请求在服务端看来也是 127.0.0.1，
 *     所以带转发头的一律拒绝</li>
 *     <li>/mcp、/api/**：配置了令牌时必须带 Authorization: Bearer &lt;token&gt;；
 *     日历订阅 /api/calendar.ics 例外地也接受 ?token=</li>
 *     <li>防跨站：服务里有个人档案，而浏览器里的任何网页都能向 localhost 发请求。所以带 Origin 的请求必须来自本机页面或
 *     浏览器插件；没配令牌时 Host 也必须是本机地址，否则恶意域名解析到 127.0.0.1 就能绕过同源策略（DNS 重绑定）</li>
 *     <li>公开模式：只放行岗位相关的只读路径，其余一律 404，并按来源 IP 限流。这里没有个人数据，所以不校验 Origin 和 Host</li>
 * </ul>
 */
public class AccessFilter extends OncePerRequestFilter {

    static final List<String> FORWARD_HEADERS = List.of(
            "X-Forwarded-For", "X-Forwarded-Host", "X-Real-IP", "Forwarded", "CF-Connecting-IP", "CF-Ray");

    /**
     * 日历应用订阅时不能带请求头，这一个只读地址允许用 ?token= 传令牌
     */
    static final String CALENDAR_PATH = "/api/calendar.ics";

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]", "host.docker.internal");

    private final String token;
    private final boolean hosted;
    private final RateLimiter perClient;
    private final RateLimiter total;

    public AccessFilter(String token) {
        this(token, false, null, null);
    }

    /**
     * 公开模式
     */
    public AccessFilter(String token, RateLimiter perClient, RateLimiter total) {
        this(token, true, perClient, total);
    }

    private AccessFilter(String token, boolean hosted, RateLimiter perClient, RateLimiter total) {
        this.token = token == null || token.isBlank() ? null : token;
        this.hosted = hosted;
        this.perClient = perClient;
        this.total = total;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/api/admin")) {
            if (!isDirectLocal(request)) {
                reject(response, HttpServletResponse.SC_FORBIDDEN, "admin endpoints are only available to direct local requests");
                return;
            }
        } else if (hosted) {
            if (!isPublicPath(path)) {
                reject(response, HttpServletResponse.SC_NOT_FOUND, "not available on the public instance");
                return;
            }
            if (!total.tryAcquire("all") || !perClient.tryAcquire(clientKey(request))) {
                response.setHeader("Retry-After", "60");
                reject(response, 429, "rate limit exceeded");
                return;
            }
        } else if (!sameSite(request)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "cross-site request rejected");
            return;
        }
        if (!path.startsWith("/api/admin") && token != null
                && (path.equals("/mcp") || path.startsWith("/mcp/") || path.startsWith("/api/"))
                && !matches(request.getHeader("Authorization"), token)
                && !(CALENDAR_PATH.equals(path) && queryTokenMatches(request.getParameter("token"), token))) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "missing or invalid bearer token");
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean isPublicPath(String path) {
        return path.equals("/mcp") || path.startsWith("/mcp/")
                || path.equals("/api/jobs") || path.startsWith("/api/jobs/")
                || path.equals("/api/sources") || path.equals("/api/status");
    }

    /**
     * 公开部署通常在 Cloudflare 或反向代理后面，这时 remoteAddr 是代理的地址
     */
    static String clientKey(HttpServletRequest request) {
        String ip = request.getHeader("CF-Connecting-IP");
        if (ip == null || ip.isBlank()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            ip = forwarded == null ? null : forwarded.split(",")[0].trim();
        }
        return ip == null || ip.isBlank() ? request.getRemoteAddr() : ip;
    }

    private boolean sameSite(HttpServletRequest request) {
        if (token == null && !isLocalHost(request.getServerName())) {
            return false;
        }
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return true;
        }
        try {
            URI uri = new URI(origin);
            if ("chrome-extension".equals(uri.getScheme()) || "moz-extension".equals(uri.getScheme())) {
                return true;
            }
            String host = uri.getHost();
            return host != null && (isLocalHost(host) || host.equalsIgnoreCase(request.getServerName()));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean isLocalHost(String host) {
        return host != null && LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    public static boolean isDirectLocal(HttpServletRequest request) {
        for (String h : FORWARD_HEADERS) {
            if (request.getHeader(h) != null) {
                return false;
            }
        }
        try {
            return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    static boolean matches(String header, String token) {
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        byte[] given = header.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, token.getBytes(StandardCharsets.UTF_8));
    }

    static boolean queryTokenMatches(String given, String token) {
        return given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
