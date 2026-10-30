package io.github.ajax1.campusradar.match;

import io.github.ajax1.campusradar.application.ApplicationRepository;
import io.github.ajax1.campusradar.collect.CollectRunRepository;
import io.github.ajax1.campusradar.job.JobViews;
import io.github.ajax1.campusradar.profile.UserProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatchCacheTest {

    private final JobMatcher matcher = mock(JobMatcher.class);
    private final CollectRunRepository runs = mock(CollectRunRepository.class);
    private final ApplicationRepository applications = mock(ApplicationRepository.class);
    private final MatchCache cache = new MatchCache(matcher, runs, applications);

    private static MatchResult result(long id, String title) {
        return new MatchResult(new JobViews.Summary(id, "字节跳动", title, null, "校招", null, List.of(), null, null,
                null, "OPEN", null, null), 80, List.of());
    }

    @Test
    void computesOnceForTheSameProfileAndData() {
        when(runs.findFirstByOrderByStartedAtDesc()).thenReturn(Optional.empty());
        when(applications.findTrackedJobIds()).thenReturn(Set.of());
        when(matcher.match(any(), anyInt(), anyInt(), any())).thenReturn(List.of(result(1, "后端")));
        UserProfile profile = mock(UserProfile.class);

        cache.match(profile, 10, 3);
        cache.match(profile, 10, 3);

        verify(matcher, times(1)).match(any(), anyInt(), anyInt(), any());
    }

    @Test
    void newlyTrackedJobsDisappearWithoutRecomputing() {
        when(runs.findFirstByOrderByStartedAtDesc()).thenReturn(Optional.empty());
        when(applications.findTrackedJobIds()).thenReturn(Set.of());
        when(matcher.match(any(), anyInt(), anyInt(), any())).thenReturn(List.of(result(1, "后端"), result(2, "算法")));
        UserProfile profile = mock(UserProfile.class);
        cache.match(profile, 10, 3);

        when(applications.findTrackedJobIds()).thenReturn(Set.of(1L));

        assertThat(cache.match(profile, 10, 3)).extracting(m -> m.job().id()).containsExactly(2L);
        verify(matcher, times(1)).match(any(), anyInt(), anyInt(), any());
    }

    @Test
    void sameTitleSplitByCityShowsOnce() {
        when(runs.findFirstByOrderByStartedAtDesc()).thenReturn(Optional.empty());
        when(applications.findTrackedJobIds()).thenReturn(Set.of());
        when(matcher.match(any(), anyInt(), anyInt(), any())).thenReturn(List.of(result(1, "后端"), result(2, "后端"), result(3, "算法")));

        assertThat(cache.match(mock(UserProfile.class), 10, 3)).extracting(m -> m.job().id()).containsExactly(1L, 3L);
    }
}
