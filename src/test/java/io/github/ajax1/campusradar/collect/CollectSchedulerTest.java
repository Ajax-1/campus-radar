package io.github.ajax1.campusradar.collect;

import io.github.ajax1.campusradar.RadarProperties;
import io.github.ajax1.campusradar.job.JobPostingRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollectSchedulerTest {

    private final CollectService collect = mock(CollectService.class);
    private final CollectRunRepository runs = mock(CollectRunRepository.class);

    private CollectScheduler scheduler(int maxAgeHours) {
        RadarProperties.Collect config = new RadarProperties.Collect("-", "Asia/Shanghai", false, 0, 0, List.of(), false, maxAgeHours);
        return new CollectScheduler(collect, new RadarProperties(config, null, null, null, null, null, null),
                mock(JobPostingRepository.class), runs);
    }

    private void lastRunHoursAgo(int hours) {
        when(runs.findFirstByOrderByStartedAtDesc()).thenReturn(Optional.of(
                new CollectRun("x", Instant.now().minus(Duration.ofHours(hours)))));
    }

    @Test
    void collectsAgainWhenDataIsStale() {
        lastRunHoursAgo(30);
        scheduler(26).catchUp();
        verify(collect).runAll();
    }

    @Test
    void collectsWhenNothingHasEverRun() {
        when(runs.findFirstByOrderByStartedAtDesc()).thenReturn(Optional.empty());
        scheduler(26).catchUp();
        verify(collect).runAll();
    }

    @Test
    void leavesFreshDataAlone() {
        lastRunHoursAgo(2);
        scheduler(26).catchUp();
        verify(collect, never()).runAll();
    }

    @Test
    void doesNotOverlapARunningCollection() {
        lastRunHoursAgo(30);
        when(collect.collecting()).thenReturn(true);
        scheduler(26).catchUp();
        verify(collect, never()).runAll();
    }

    @Test
    void zeroDisablesCatchUp() {
        lastRunHoursAgo(30);
        scheduler(0).catchUp();
        verify(collect, never()).runAll();
    }
}
