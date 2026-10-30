package io.github.ajax1.campusradar.mcp;

import io.github.ajax1.campusradar.application.Application;
import io.github.ajax1.campusradar.application.ApplicationService;
import io.github.ajax1.campusradar.application.ApplicationStatus;
import io.github.ajax1.campusradar.application.ApplicationView;
import io.github.ajax1.campusradar.mail.MailSyncService;
import io.github.ajax1.campusradar.schedule.EventKind;
import io.github.ajax1.campusradar.schedule.EventView;
import io.github.ajax1.campusradar.schedule.ScheduleService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 投递台账和日程。投递本身由用户在官网完成，这里只记录和跟进。
 */
@Component
public class ApplicationTools {

    private final ApplicationService applications;
    private final ScheduleService schedule;
    private final MailSyncService mail;

    public ApplicationTools(ApplicationService applications, ScheduleService schedule, MailSyncService mail) {
        this.applications = applications;
        this.schedule = schedule;
        this.mail = mail;
    }

    @Tool(name = "record_application", description = """
            用户在官网投递后记一笔。传 jobId（search_jobs / match_jobs 返回的 id）时公司、岗位名、链接自动补全；
            不在岗位库里的公司直接传 company 和 title。同一岗位重复记录会更新原记录，不会重复。""")
    public ApplicationView recordApplication(
            @ToolParam(required = false, description = "岗位库里的岗位 id") Long jobId,
            @ToolParam(required = false, description = "公司名，不传 jobId 时必填") String company,
            @ToolParam(required = false, description = "岗位名") String title,
            @ToolParam(required = false, description = "状态，默认 已投递；可选 想投、已投递、笔试/测评、面试中、Offer、未通过、已放弃") String status,
            @ToolParam(required = false, description = "备注，如 内推码、投递渠道") String note) {
        return applications.record(company, title, jobId, null, parseStatus(status), Application.Source.AGENT, note);
    }

    @Tool(name = "update_application", description = "修改投递状态或加备注，比如用户说面试挂了、拿到 Offer、决定放弃")
    public ApplicationView updateApplication(
            @ToolParam(description = "投递记录 id，见 list_applications") long id,
            @ToolParam(required = false, description = "新状态：想投、已投递、笔试/测评、面试中、Offer、未通过、已放弃") String status,
            @ToolParam(required = false, description = "备注") String note) {
        return applications.update(id, parseStatus(status), note);
    }

    @Tool(name = "list_applications", description = "列出投递台账和每条的时间线（含邮件自动识别出的笔试、面试、拒信），按最近更新排序")
    public List<ApplicationView> listApplications(
            @ToolParam(required = false, description = "只看某个状态，如 面试中") String status) {
        return applications.list(parseStatus(status));
    }

    @Tool(name = "list_events", description = "列出接下来的笔试、面试、测评日程（含测评截止时间）；localTime 是北京时间，配置了当地时区时括号里附带当地时间")
    public List<EventView> listEvents(@ToolParam(required = false, description = "看未来几天，默认 14") Integer days) {
        return schedule.upcoming(days == null ? 14 : days);
    }

    @Tool(name = "add_event", description = "手动添加笔试、面试、测评日程（邮件没识别到时用），会自动推进对应公司的投递状态并按时提醒")
    public EventView addEvent(
            @ToolParam(description = "公司名") String company,
            @ToolParam(description = "类型：面试、笔试、测评") String kind,
            @ToolParam(description = "开始时间，格式 yyyy-MM-dd HH:mm，如 2026-10-28 19:00") String start,
            @ToolParam(required = false, description = "start 的时区，默认北京时间；海外面试填如 America/New_York") String timezone,
            @ToolParam(required = false, description = "时长（分钟），默认 60") Integer durationMinutes,
            @ToolParam(required = false, description = "会议或笔试链接") String link,
            @ToolParam(required = false, description = "线下地点") String location) {
        EventKind k = EventKind.parse(kind);
        if (k == null) {
            throw new IllegalArgumentException("kind 只能是 面试、笔试、测评，收到：" + kind);
        }
        return schedule.add(company, k, start, timezone, durationMinutes == null ? 60 : durationMinutes, link, location);
    }

    @Tool(name = "sync_mail", description = "立即同步一次邮箱，识别新的笔试、面试邀请和 Offer、拒信（平时每 15 分钟自动同步）")
    public MailSyncService.Report syncMail() {
        return mail.syncNow();
    }

    private static ApplicationStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        ApplicationStatus s = ApplicationStatus.parse(status);
        if (s == null) {
            throw new IllegalArgumentException("status 只能是 想投、已投递、笔试/测评、面试中、Offer、未通过、已放弃，收到：" + status);
        }
        return s;
    }
}
