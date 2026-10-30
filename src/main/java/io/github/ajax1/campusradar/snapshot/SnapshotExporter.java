package io.github.ajax1.campusradar.snapshot;

import io.github.ajax1.campusradar.job.JobPosting;
import io.github.ajax1.campusradar.job.JobPostingRepository;
import io.github.ajax1.campusradar.job.JobStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * 把在招岗位写成 gzip 压缩的快照。先写临时文件再改名，读的人不会读到写了一半的文件
 */
@Service
public class SnapshotExporter {

    private final JobPostingRepository jobs;

    public SnapshotExporter(JobPostingRepository jobs) {
        this.jobs = jobs;
    }

    /**
     * @return 写入的岗位数
     */
    @Transactional(readOnly = true)
    public int export(Path target) throws IOException {
        List<JobPosting> open = jobs.findByStatus(JobStatus.OPEN);
        Path absolute = target.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path tmp = absolute.resolveSibling(absolute.getFileName() + ".tmp");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
            out.write(SnapshotFormat.write(open, Instant.now()).getBytes(StandardCharsets.UTF_8));
        }
        Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return open.size();
    }
}
