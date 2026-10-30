package io.github.ajax1.campusradar.snapshot;

import io.github.ajax1.campusradar.RadarProperties;
import io.github.ajax1.campusradar.collect.CollectException;
import io.github.ajax1.campusradar.collect.RawJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 下载并解析快照。一轮采集里每个数据源都会来取，所以结果缓存一段时间，只下载一次；下载失败也缓存，不反复重试。
 */
@Component
public class SnapshotSource {

    private static final Logger log = LoggerFactory.getLogger(SnapshotSource.class);

    static final long MAX_BYTES = 256L * 1024 * 1024;
    private static final Duration TTL = Duration.ofMinutes(30);

    private final String url;
    private final Clock clock;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private Map<String, List<RawJob>> cached;
    private CollectException failure;
    private Instant fetchedAt;

    @Autowired
    public SnapshotSource(RadarProperties properties) {
        this(properties.snapshot().url(), Clock.systemUTC());
    }

    SnapshotSource(String url, Clock clock) {
        this.url = url;
        this.clock = clock;
    }

    public synchronized Map<String, List<RawJob>> jobs() {
        if (fetchedAt == null || fetchedAt.plus(TTL).isBefore(clock.instant())) {
            fetchedAt = clock.instant();
            try {
                cached = SnapshotFormat.parse(download());
                failure = null;
                log.info("快照下载完成，{} 个数据源", cached.size());
            } catch (IOException | RuntimeException e) {
                cached = null;
                failure = new CollectException("快照下载失败: " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }
        if (failure != null) {
            throw failure;
        }
        return cached;
    }

    private String download() throws IOException {
        try (InputStream raw = open(); InputStream in = new GZIPInputStream(raw)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            for (int n; (n = in.read(buffer)) > 0; ) {
                total += n;
                if (total > MAX_BYTES) {
                    throw new IOException("快照超过大小上限");
                }
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private InputStream open() throws IOException {
        URI uri = URI.create(url.trim());
        if ("file".equals(uri.getScheme())) {
            return Files.newInputStream(Path.of(uri));
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET().build();
        try {
            HttpResponse<InputStream> resp = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                resp.body().close();
                throw new IOException("HTTP " + resp.statusCode());
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断", e);
        }
    }
}
