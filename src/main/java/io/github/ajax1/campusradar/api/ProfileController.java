package io.github.ajax1.campusradar.api;

import io.github.ajax1.campusradar.match.MatchCache;
import io.github.ajax1.campusradar.match.MatchResult;
import io.github.ajax1.campusradar.profile.ProfileService;
import io.github.ajax1.campusradar.profile.ProfileView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ProfileController {

    private final ProfileService profiles;
    private final MatchCache matches;

    public ProfileController(ProfileService profiles, MatchCache matches) {
        this.profiles = profiles;
        this.matches = matches;
    }

    @GetMapping("/profile")
    public ProfileView get() {
        return profiles.view();
    }

    /**
     * 部分更新：不传的字段不改，列表传 [] 清空
     */
    @PutMapping("/profile")
    public ProfileView update(@RequestBody ProfileView change) {
        return profiles.update(change);
    }

    @GetMapping("/match")
    public List<MatchResult> match(@RequestParam(defaultValue = "10") int limit,
                                   @RequestParam(defaultValue = "3") int maxPerCompany) {
        return matches.match(profiles.current(), Math.clamp(limit, 1, 50), maxPerCompany);
    }
}
