package io.github.ajax1.campusradar.match;

import io.github.ajax1.campusradar.application.ApplicationRepository;
import io.github.ajax1.campusradar.collect.CollectRun;
import io.github.ajax1.campusradar.collect.CollectRunRepository;
import io.github.ajax1.campusradar.profile.UserProfile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 全量打分要扫一遍所有 JD，岗位上万时要好几秒。档案和岗位库都没变时结果不会变，所以网页和 Agent 都走这里。
 * 算一次时多算一些，用户把岗位加进台账后直接从结果里滤掉，不用重算。
 */
@Service
public class MatchCache {

    static final int CACHED_RESULTS = 120;

    private record Key(Instant profileUpdatedAt, Instant lastCollect, int maxPerCompany) {
    }

    private final JobMatcher matcher;
    private final CollectRunRepository runs;
    private final ApplicationRepository applications;
    private Key key;
    private List<MatchResult> results;

    public MatchCache(JobMatcher matcher, CollectRunRepository runs, ApplicationRepository applications) {
        this.matcher = matcher;
        this.runs = runs;
        this.applications = applications;
    }

    public synchronized List<MatchResult> match(UserProfile profile, int limit, int maxPerCompany) {
        Key current = new Key(profile.getUpdatedAt(),
                runs.findFirstByOrderByStartedAtDesc().map(CollectRun::getStartedAt).orElse(null), maxPerCompany);
        if (!current.equals(key)) {
            results = matcher.match(profile, CACHED_RESULTS, maxPerCompany, Set.of());
            key = current;
        }
        Set<Long> tracked = Set.copyOf(applications.findTrackedJobIds());
        // 同一个岗位常按城市拆成多条，名字完全相同的只留分最高的一条
        Set<String> seen = new HashSet<>();
        return results.stream()
                .filter(m -> !tracked.contains(m.job().id()))
                .filter(m -> seen.add(m.job().company() + "|" + m.job().title()))
                .limit(limit)
                .toList();
    }
}
