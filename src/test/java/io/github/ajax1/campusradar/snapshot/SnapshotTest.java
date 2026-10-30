package io.github.ajax1.campusradar.snapshot;

import io.github.ajax1.campusradar.collect.CollectException;
import io.github.ajax1.campusradar.collect.RawJob;
import io.github.ajax1.campusradar.job.JobPosting;
import io.github.ajax1.campusradar.job.JobStatus;
import io.github.ajax1.campusradar.job.RecruitType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotTest {

    private static JobPosting posting(String source, String id, String title) {
        JobPosting j = new JobPosting();
        j.setSource(source);
        j.setExternalId(id);
        j.setCompany("测试公司");
        j.setTitle(title);
        j.setCategory("技术");
        j.setRecruitType(RecruitType.INTERN);
        j.setGradYear(2027);
        j.setCities(",北京,上海,");
        j.setDescription("写代码");
        j.setRequirement("会 Java");
        j.setApplyUrl("https://example.com/" + id);
        j.setPublishedAt(Instant.parse("2026-10-01T00:00:00Z"));
        j.setStatus(JobStatus.OPEN);
        return j;
    }

    @Test
    void roundTripKeepsEveryFieldTheCollectorNeeds() {
        String json = SnapshotFormat.write(List.of(posting("a", "1", "后端实习生"), posting("b", "2", "算法实习生")), Instant.now());

        Map<String, List<RawJob>> parsed = SnapshotFormat.parse(json);

        assertThat(parsed).containsOnlyKeys("a", "b");
        RawJob job = parsed.get("a").getFirst();
        assertThat(job.externalId()).isEqualTo("1");
        assertThat(job.title()).isEqualTo("后端实习生");
        assertThat(job.recruitType()).isEqualTo(RecruitType.INTERN);
        assertThat(job.gradYear()).isEqualTo(2027);
        assertThat(job.cities()).containsExactly("北京", "上海");
        assertThat(job.publishedAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
    }

    @Test
    void rejectsSnapshotsFromANewerFormat() {
        assertThatThrownBy(() -> SnapshotFormat.parse("{\"version\":99,\"jobs\":[]}"))
                .isInstanceOf(CollectException.class).hasMessageContaining("升级");
    }

    @Test
    void downloadsGzipOnceAndCachesFailures(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("jobs.json.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(SnapshotFormat.write(List.of(posting("a", "1", "后端")), Instant.now()).getBytes(StandardCharsets.UTF_8));
        }
        Clock clock = Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"), ZoneOffset.UTC);
        SnapshotSource source = new SnapshotSource(file.toUri().toString(), clock);

        assertThat(source.jobs().get("a")).hasSize(1);

        Files.delete(file);
        // 同一轮采集里不重新下载
        assertThat(source.jobs().get("a")).hasSize(1);

        SnapshotSource missing = new SnapshotSource(file.toUri().toString(), clock);
        assertThatThrownBy(missing::jobs).isInstanceOf(CollectException.class).hasMessageContaining("快照下载失败");
        assertThatThrownBy(missing::jobs).isInstanceOf(CollectException.class);
    }
}
