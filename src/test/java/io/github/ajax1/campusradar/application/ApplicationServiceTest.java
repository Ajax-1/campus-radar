package io.github.ajax1.campusradar.application;

import io.github.ajax1.campusradar.collect.JobUpsertService;
import io.github.ajax1.campusradar.job.JobPostingRepository;
import io.github.ajax1.campusradar.match.MatchFixtures;
import io.github.ajax1.campusradar.push.PushRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ApplicationServiceTest {

    @Autowired
    ApplicationService service;
    @Autowired
    ApplicationRepository applications;
    @Autowired
    ApplicationLogRepository logs;
    @Autowired
    JobPostingRepository jobs;
    @Autowired
    PushRecordRepository pushes;
    @Autowired
    JobUpsertService upsert;

    @BeforeEach
    void setUp() {
        logs.deleteAll();
        applications.deleteAll();
        pushes.deleteAll();
        jobs.deleteAll();
    }

    @Test
    void recordFromJobFillsDetailsAndDoesNotDuplicate() {
        MatchFixtures.seed(upsert);
        long jobId = jobs.findBySourceAndExternalId("bytedance", "b1").orElseThrow().getId();

        ApplicationView first = service.record(null, null, jobId, null, null, Application.Source.EXTENSION, "内推码 ABC");
        assertThat(first.company()).isEqualTo("字节跳动");
        assertThat(first.title()).isEqualTo("后端开发工程师-抖音");
        assertThat(first.applyUrl()).isEqualTo("https://example.com/b1");
        assertThat(first.status()).isEqualTo("已投递");
        assertThat(first.timeline()).extracting(ApplicationView.Entry::note).containsExactly("浏览器插件记录：内推码 ABC");

        ApplicationView again = service.record(null, null, jobId, null, null, Application.Source.EXTENSION, null);
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(applications.count()).isEqualTo(1);
    }

    @Test
    void companiesOutsideCatalogAreKeptAsIs() {
        ApplicationView v = service.record("华为", "软件开发工程师", null, null, null, Application.Source.MANUAL, null);
        assertThat(v.company()).isEqualTo("华为");
        assertThat(service.record("字节", "后端", null, null, null, Application.Source.MANUAL, null).company())
                .isEqualTo("字节跳动");
        assertThatThrownBy(() -> service.record(null, null, null, null, null, Application.Source.MANUAL, null))
                .hasMessageContaining("company 和 jobId");
    }

    @Test
    void mailOnlyMovesForwardButRejectionAlwaysWins() {
        service.record("美团", "后端", null, null, null, Application.Source.MANUAL, null);
        assertThat(service.advanceFromMail("美团", ApplicationStatus.INTERVIEW, "一面邀请").getStatus())
                .isEqualTo(ApplicationStatus.INTERVIEW);
        // 面试中又收到测评邮件：不倒退
        assertThat(service.advanceFromMail("美团", ApplicationStatus.ASSESSMENT, "补测评").getStatus())
                .isEqualTo(ApplicationStatus.INTERVIEW);
        assertThat(service.advanceFromMail("美团", ApplicationStatus.REJECTED, "感谢信").getStatus())
                .isEqualTo(ApplicationStatus.REJECTED);

        // 已结束的投递不再被推进；同公司新的邀请会新建一条（比如投了另一个岗位）
        Application fresh = service.advanceFromMail("美团", ApplicationStatus.INTERVIEW, "另一个岗位的面试");
        assertThat(fresh.getSource()).isEqualTo(Application.Source.MAIL);
        assertThat(applications.count()).isEqualTo(2);
    }

    @Test
    void manualUpdateCanGoBackwards() {
        ApplicationView v = service.record("百度", "后端", null, null, ApplicationStatus.REJECTED,
                Application.Source.MANUAL, null);
        ApplicationView fixed = service.update(v.id(), ApplicationStatus.INTERVIEW, "误标了，其实还在面试");
        assertThat(fixed.status()).isEqualTo("面试中");
        assertThat(fixed.timeline()).hasSize(2);
    }
}
