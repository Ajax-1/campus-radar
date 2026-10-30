package io.github.ajax1.campusradar.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ScheduleEventRepository extends JpaRepository<ScheduleEvent, Long> {

    boolean existsBySourceKey(String sourceKey);

    List<ScheduleEvent> findByCancelledFalseAndStartAtBetweenOrderByStartAtAsc(Instant from, Instant to);

    List<ScheduleEvent> findByCancelledFalseAndStartAtAfterOrderByStartAtAsc(Instant from);

    List<ScheduleEvent> findByApplicationIdOrderByStartAtAsc(Long applicationId);
}
