package io.github.ajax1.campusradar.application;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApplicationLogRepository extends JpaRepository<ApplicationLog, Long> {

    List<ApplicationLog> findByApplicationIdOrderByAtAsc(Long applicationId);
}
