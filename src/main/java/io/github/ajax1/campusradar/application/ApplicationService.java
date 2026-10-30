package io.github.ajax1.campusradar.application;

import io.github.ajax1.campusradar.collect.CompanyDirectory;
import io.github.ajax1.campusradar.job.JobPosting;
import io.github.ajax1.campusradar.job.JobPostingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 投递台账。投递可以来自不在岗位库里的公司（比如华为），所以公司名能解析就用标准名，解析不了就原样保存。
 */
@Service
public class ApplicationService {

    private final ApplicationRepository applications;
    private final ApplicationLogRepository logs;
    private final JobPostingRepository jobs;
    private final CompanyDirectory companies;

    public ApplicationService(ApplicationRepository applications, ApplicationLogRepository logs,
                              JobPostingRepository jobs, CompanyDirectory companies) {
        this.applications = applications;
        this.logs = logs;
        this.jobs = jobs;
        this.companies = companies;
    }

    /**
     * 记录一次投递。传了 jobId 时公司、岗位名、链接从岗位库补全。同公司同岗位已有记录时更新那条，不重复建。
     */
    @Transactional
    public ApplicationView record(String company, String title, Long jobId, String applyUrl,
                                  ApplicationStatus status, Application.Source source, String note) {
        if (jobId != null) {
            JobPosting job = jobs.findById(jobId).orElseThrow(() -> new IllegalArgumentException("岗位不存在: " + jobId));
            company = job.getCompany();
            title = title == null || title.isBlank() ? job.getTitle() : title;
            applyUrl = applyUrl == null || applyUrl.isBlank() ? job.getApplyUrl() : applyUrl;
        }
        if (company == null || company.isBlank()) {
            throw new IllegalArgumentException("company 和 jobId 至少要给一个");
        }
        String canonical = canonicalCompany(company);
        String cleanTitle = title == null || title.isBlank() ? null : title.trim();
        ApplicationStatus target = status == null ? ApplicationStatus.APPLIED : status;
        Instant now = Instant.now();

        Optional<Application> existing = findSame(canonical, cleanTitle, jobId);
        Application a;
        if (existing.isPresent()) {
            a = existing.get();
            if (a.getTitle() == null && cleanTitle != null) {
                a.setTitle(cleanTitle);
            }
            if (a.getStatus() != target) {
                a.setStatus(target);
                logs.save(new ApplicationLog(a.getId(), now, target, describe(source, note)));
            }
        } else {
            a = new Application(canonical, cleanTitle, target, source, now);
            a = applications.save(a);
            logs.save(new ApplicationLog(a.getId(), now, target, describe(source, note)));
        }
        if (jobId != null) {
            a.setJobId(jobId);
        }
        if (applyUrl != null && !applyUrl.isBlank()) {
            a.setApplyUrl(applyUrl.trim());
        }
        if (note != null && !note.isBlank()) {
            a.setNote(note.trim());
        }
        a.setUpdatedAt(now);
        return view(a);
    }

    /**
     * 手动改状态或加备注，状态可以任意改（包括回退，比如误标了未通过）
     */
    @Transactional
    public ApplicationView update(long id, ApplicationStatus status, String note) {
        Application a = applications.findById(id).orElseThrow(() -> new IllegalArgumentException("投递记录不存在: " + id));
        Instant now = Instant.now();
        if (status != null && status != a.getStatus()) {
            a.setStatus(status);
        }
        if (note != null && !note.isBlank()) {
            a.setNote(note.trim());
        }
        if (status != null || (note != null && !note.isBlank())) {
            logs.save(new ApplicationLog(a.getId(), now, status, note));
            a.setUpdatedAt(now);
        }
        return view(a);
    }

    /**
     * 邮件里识别出笔试、面试、Offer、拒信时调用：找到这家公司最近一条还没结束的投递往前推，没有就新建一条。
     * 只前进不后退：已经在面试的不会因为又收到一封测评邮件退回笔试。拒信和 Offer 总是生效。
     *
     * @return 被更新或新建的投递
     */
    @Transactional
    public Application advanceFromMail(String company, ApplicationStatus status, String evidence) {
        String canonical = canonicalCompany(company);
        Instant now = Instant.now();
        Application a = applications.findByCompanyOrderByUpdatedAtDesc(canonical).stream()
                .filter(x -> !x.getStatus().isFinal())
                .findFirst()
                .orElse(null);
        if (a == null) {
            a = applications.save(new Application(canonical, null, status, Application.Source.MAIL, now));
            logs.save(new ApplicationLog(a.getId(), now, status, "邮件自动建档：" + evidence));
            return a;
        }
        boolean forward = status.ordinal() > a.getStatus().ordinal() || status.isFinal();
        if (forward && status != a.getStatus()) {
            a.setStatus(status);
            a.setUpdatedAt(now);
            logs.save(new ApplicationLog(a.getId(), now, status, "邮件：" + evidence));
        }
        return a;
    }

    @Transactional(readOnly = true)
    public List<ApplicationView> list(ApplicationStatus status) {
        List<Application> list = status == null
                ? applications.findAllByOrderByUpdatedAtDesc()
                : applications.findByStatusOrderByUpdatedAtDesc(status);
        return list.stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public Optional<ApplicationView> get(long id) {
        return applications.findById(id).map(this::view);
    }

    public String canonicalCompany(String company) {
        return companies.resolve(company).orElse(company.trim());
    }

    private Optional<Application> findSame(String company, String title, Long jobId) {
        return applications.findByCompanyOrderByUpdatedAtDesc(company).stream()
                .filter(a -> (jobId != null && jobId.equals(a.getJobId()))
                        || (title != null && Objects.equals(title, a.getTitle())))
                .findFirst();
    }

    private ApplicationView view(Application a) {
        return ApplicationView.of(a, logs.findByApplicationIdOrderByAtAsc(a.getId()));
    }

    private static String describe(Application.Source source, String note) {
        String from = switch (source) {
            case MANUAL -> "手动记录";
            case EXTENSION -> "浏览器插件记录";
            case MAIL -> "邮件";
            case AGENT -> "Agent 记录";
        };
        return note == null || note.isBlank() ? from : from + "：" + note.trim();
    }
}
