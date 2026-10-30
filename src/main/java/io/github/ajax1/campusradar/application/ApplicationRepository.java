package io.github.ajax1.campusradar.application;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Set;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    List<Application> findByCompanyOrderByUpdatedAtDesc(String company);

    List<Application> findAllByOrderByUpdatedAtDesc();

    List<Application> findByStatusOrderByUpdatedAtDesc(ApplicationStatus status);

    /**
     * 已经在台账里的岗位（投过、想投、面试中……），匹配推荐时跳过
     */
    @Query("select a.jobId from Application a where a.jobId is not null")
    Set<Long> findTrackedJobIds();
}
