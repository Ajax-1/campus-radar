package io.github.ajax1.campusradar.mail;

import io.github.ajax1.campusradar.RadarProperties;
import io.github.ajax1.campusradar.application.Application;
import io.github.ajax1.campusradar.application.ApplicationRepository;
import io.github.ajax1.campusradar.application.ApplicationService;
import io.github.ajax1.campusradar.application.ApplicationStatus;
import io.github.ajax1.campusradar.collect.CompanyDirectory;
import io.github.ajax1.campusradar.schedule.EventKind;
import io.github.ajax1.campusradar.schedule.EventView;
import io.github.ajax1.campusradar.schedule.ScheduleEvent;
import io.github.ajax1.campusradar.schedule.ScheduleEventRepository;
import io.github.ajax1.campusradar.schedule.ScheduleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 处理一封邮件：识别 → 推进投递状态 → 生成日程 → 记下已处理。每封邮件一个事务，一封出错不影响别的。
 */
@Service
public class MailProcessor {

    static final String UNKNOWN_COMPANY = "未知公司";

    /**
     * @param changes 给人看的变化描述，用于同步后的推送，如「字节跳动：已投递 → 面试中」
     */
    public record Result(MailFinding finding, List<EventView> newEvents, List<String> changes) {
        public boolean changedAnything() {
            return !newEvents.isEmpty() || !changes.isEmpty();
        }
    }

    private final InviteParser parser;
    private final MailSeenRepository seen;
    private final ApplicationService applications;
    private final ApplicationRepository applicationRepository;
    private final ScheduleEventRepository events;
    private final CompanyDirectory companies;
    private final ScheduleService schedule;

    public MailProcessor(MailSeenRepository seen, ApplicationService applications,
                         ApplicationRepository applicationRepository, ScheduleEventRepository events,
                         CompanyDirectory companies, ScheduleService schedule, RadarProperties properties) {
        this.parser = new InviteParser(ZoneId.of(properties.mail().zone()));
        this.schedule = schedule;
        this.seen = seen;
        this.applications = applications;
        this.applicationRepository = applicationRepository;
        this.events = events;
        this.companies = companies;
    }

    @Transactional(readOnly = true)
    public boolean alreadySeen(String messageId) {
        return seen.existsById(messageId);
    }

    @Transactional
    public Result process(MailMessage m) {
        if (seen.existsById(m.messageId())) {
            return new Result(MailFinding.none(), List.of(), List.of());
        }
        MailFinding f = parser.parse(m, knownCompanies());
        List<EventView> newEvents = new ArrayList<>();
        List<String> changes = new ArrayList<>();

        ApplicationStatus implied = switch (f.type()) {
            case INTERVIEW -> ApplicationStatus.INTERVIEW;
            case WRITTEN_TEST, ASSESSMENT -> ApplicationStatus.ASSESSMENT;
            case OFFER -> ApplicationStatus.OFFER;
            case REJECTION -> ApplicationStatus.REJECTED;
            case NONE -> null;
        };
        Long applicationId = null;
        String appTitle = null;
        if (implied != null && f.company() != null) {
            ApplicationStatus before = applicationRepository.findByCompanyOrderByUpdatedAtDesc(
                            applications.canonicalCompany(f.company())).stream()
                    .filter(a -> !a.getStatus().isFinal()).findFirst().map(Application::getStatus).orElse(null);
            Application app = applications.advanceFromMail(f.company(), implied, shorten(m.subject()));
            applicationId = app.getId();
            appTitle = app.getTitle();
            if (before != app.getStatus()) {
                changes.add(app.getCompany() + "：" + (before == null ? "新建投递记录" : before.label()) + " → "
                        + app.getStatus().label());
            }
        }

        EventKind kind = switch (f.type()) {
            case INTERVIEW -> EventKind.INTERVIEW;
            case WRITTEN_TEST -> EventKind.WRITTEN_TEST;
            case ASSESSMENT -> EventKind.ASSESSMENT;
            default -> null;
        };
        if (kind != null) {
            String company = f.company() == null ? UNKNOWN_COMPANY : applications.canonicalCompany(f.company());
            boolean candidates = f.slots().size() > 1;
            for (MailFinding.Slot slot : f.slots()) {
                String key = m.messageId() + "|" + slot.start().toEpochMilli();
                if (events.existsBySourceKey(key)) {
                    continue;
                }
                String title = company + " · " + kind.label() + (appTitle == null ? "" : " · " + appTitle)
                        + (candidates ? "（候选时间，待确认）" : "");
                ScheduleEvent e = new ScheduleEvent(company, kind, title, slot.start(), slot.end(), slot.deadline(),
                        Instant.now());
                e.setApplicationId(applicationId);
                e.setLink(f.link());
                e.setLocation(f.location());
                e.setSourceKey(key);
                e.setSourceSubject(shorten(m.subject()));
                newEvents.add(schedule.view(events.save(e)));
            }
        }

        seen.save(new MailSeen(m.messageId(), f.type(), f.company(), m.subject(), m.receivedAt(), Instant.now()));
        return new Result(f, newEvents, changes);
    }

    /**
     * 岗位库里的公司 + 投递台账里的公司（可能有不在岗位库的，比如华为）
     */
    private Set<String> knownCompanies() {
        Set<String> out = new LinkedHashSet<>(companies.companies());
        applicationRepository.findAll().forEach(a -> out.add(a.getCompany()));
        return out;
    }

    private static String shorten(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 120 ? s : s.substring(0, 120);
    }
}
