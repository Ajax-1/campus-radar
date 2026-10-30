package io.github.ajax1.campusradar.mail;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则式解析笔试、面试、测评邀请和 Offer、拒信。不调用大模型：免费、可测试，误判可以通过加用例和规则修正。
 * <p>
 * 识别不了的邮件返回 {@link MailFinding.Type#NONE}，宁可漏也不乱建日程。
 */
public class InviteParser {

    private static final Pattern REJECTION = Pattern.compile(
            "很遗憾.{0,40}(未能|无法|不能|没能|未通过|没有通过|不匹配|暂不|暂时没有)|未能进入下一|不再推进"
                    + "|regret to inform|unfortunately,? (we|you)|not (be )?moving forward|decided not to proceed",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern OFFER = Pattern.compile(
            "录用通知|录用意向|offer ?letter|意向书|恭喜.{0,20}(录用|offer)|拟录用|we are pleased to offer",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern INTERVIEW = Pattern.compile("面试|interview", Pattern.CASE_INSENSITIVE);
    private static final Pattern WRITTEN = Pattern.compile(
            "笔试|机试|在线考试|编程测试|编程题|coding (test|challenge)|online assessment|\\bOA\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASSESS = Pattern.compile(
            "测评|测试邀请|性格测试|能力测试|assessment", Pattern.CASE_INSENSITIVE);

    /**
     * 只把这类邮件当作日程：营销邮件里也常出现「面试技巧」「笔试真题」
     */
    private static final Pattern NOISE = Pattern.compile("技巧|真题|课程|直播课|训练营|题库|内推群|报名立减|优惠");

    private static final String HOUR = "(上午|中午|下午|傍晚|晚上|早上)?\\s*(\\d{1,2})\\s*(?:[:：点时]\\s*(\\d{1,2}|半)?\\s*分?)";
    private static final Pattern FULL_DATE = Pattern.compile(
            "(20\\d{2})\\s*[年/\\-.]\\s*(\\d{1,2})\\s*[月/\\-.]\\s*(\\d{1,2})\\s*[日号]?");
    private static final Pattern MONTH_DAY = Pattern.compile("(?<![\\d/.\\-])(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]");
    private static final Pattern WEEKDAY = Pattern.compile(
            "^\\s*[（(]?\\s*(周|星期|礼拜)[一二三四五六日天]\\s*[)）]?\\s*[,，]?");
    private static final Pattern TIME_AFTER_DATE = Pattern.compile("^\\s*(?:at\\s*)?" + HOUR);
    private static final Pattern RANGE_END = Pattern.compile("^\\s*[-–—~～至到]\\s*" + HOUR);
    private static final Pattern DEADLINE_AFTER = Pattern.compile("^\\s*(前|之前|以前|截止|为止)");
    private static final Pattern DEADLINE_BEFORE = Pattern.compile(
            "(截止|截至|deadline|有效期至|请于|请在|务必在|due)\\s*(时间|日期)?\\s*[:：为]?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EN_DATE = Pattern.compile(
            "\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s*(20\\d{2})?"
                    + "(?:\\s*(?:at|,)?\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'）)】」，,]+");
    private static final Pattern LOCATION = Pattern.compile("(面试地点|笔试地点|地点|地址|location)\\s*[:：]\\s*([^\\n]{2,100})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BRACKET_COMPANY = Pattern.compile("^\\s*[【\\[](.{2,20}?)[】\\]]");
    private static final Pattern SENDER_COMPANY = Pattern.compile(
            "^(.{2,20}?)(集团|公司|科技)?\\s*(校园招聘|校招|招聘团队|招聘|人力资源|HR|Recruiting|Careers)", Pattern.CASE_INSENSITIVE);

    /**
     * 会议、笔试平台的域名优先作为链接
     */
    private static final List<String> PREFERRED_LINK_HOSTS = List.of(
            "meeting.tencent.com", "vc.feishu.cn", "meetings.feishu.cn", "zoom.us", "teams.microsoft", "meet.google",
            "nowcoder.com", "hackerrank", "codility", "codesignal", "mokahr.com", "zhiye.com", "beisen", "hotjob.cn",
            "jobs.feishu.cn", "jobs.bytedance.com", "talent.alibaba", "join.qq.com");

    private static final Map<String, String> DOMAIN_COMPANY = new LinkedHashMap<>();

    static {
        DOMAIN_COMPANY.put("bytedance.com", "字节跳动");
        DOMAIN_COMPANY.put("alibaba-inc.com", "阿里巴巴");
        DOMAIN_COMPANY.put("alibaba.com", "阿里巴巴");
        DOMAIN_COMPANY.put("tencent.com", "腾讯");
        DOMAIN_COMPANY.put("meituan.com", "美团");
        DOMAIN_COMPANY.put("baidu.com", "百度");
        DOMAIN_COMPANY.put("jd.com", "京东");
        DOMAIN_COMPANY.put("kuaishou.com", "快手");
        DOMAIN_COMPANY.put("pinduoduo.com", "拼多多");
        DOMAIN_COMPANY.put("pddglobalhr.com", "拼多多");
        DOMAIN_COMPANY.put("nio.com", "蔚来");
        DOMAIN_COMPANY.put("xiaopeng.com", "小鹏汽车");
        DOMAIN_COMPANY.put("huawei.com", "华为");
        DOMAIN_COMPANY.put("netease.com", "网易");
        DOMAIN_COMPANY.put("didiglobal.com", "滴滴");
        DOMAIN_COMPANY.put("xiaomi.com", "小米");
    }

    private final ZoneId defaultZone;

    public InviteParser(ZoneId defaultZone) {
        this.defaultZone = defaultZone;
    }

    /**
     * @param knownCompanies 已知公司名（岗位库 + 投递台账），在主题、正文、发件人里找
     */
    public MailFinding parse(MailMessage m, Collection<String> knownCompanies) {
        String subject = normalize(nullToEmpty(m.subject()));
        String text = normalize(nullToEmpty(m.text()));
        String all = subject + "\n" + text;

        MailFinding.Type type = classify(subject, text);
        if (type == MailFinding.Type.NONE) {
            return MailFinding.none();
        }
        String company = detectCompany(m, subject, text, knownCompanies);
        List<MailFinding.Slot> slots = type == MailFinding.Type.OFFER || type == MailFinding.Type.REJECTION
                ? List.of()
                : extractSlots(all, m.receivedAt(), zoneOf(all));
        return new MailFinding(type, company, slots, extractLink(text), extractLocation(text));
    }

    static MailFinding.Type classify(String subject, String text) {
        String all = subject + "\n" + text;
        if (REJECTION.matcher(all).find()) {
            return MailFinding.Type.REJECTION;
        }
        if (OFFER.matcher(subject).find() || OFFER.matcher(text).find() && !INTERVIEW.matcher(subject).find()) {
            return MailFinding.Type.OFFER;
        }
        // 「面试技巧直播课」这类营销主题直接忽略
        if (NOISE.matcher(subject).find()) {
            return MailFinding.Type.NONE;
        }
        // 主题里的关键词说了算；主题没有时才看正文，并且排除营销邮件
        for (String source : new String[]{subject, text}) {
            if (source == text && NOISE.matcher(subject + text).find()) {
                break;
            }
            if (INTERVIEW.matcher(source).find()) {
                return MailFinding.Type.INTERVIEW;
            }
            if (WRITTEN.matcher(source).find()) {
                return MailFinding.Type.WRITTEN_TEST;
            }
            if (ASSESS.matcher(source).find()) {
                return MailFinding.Type.ASSESSMENT;
            }
        }
        return MailFinding.Type.NONE;
    }

    String detectCompany(MailMessage m, String subject, String text, Collection<String> knownCompanies) {
        // 已知公司名按长度倒序，避免「阿里」先于「阿里巴巴」命中
        List<String> known = knownCompanies.stream().filter(c -> c != null && c.length() >= 2)
                .sorted((a, b) -> b.length() - a.length()).toList();
        String fromName = normalize(nullToEmpty(m.fromName()));
        for (String source : new String[]{subject, fromName}) {
            for (String c : known) {
                if (source.contains(c)) {
                    return c;
                }
            }
        }
        Matcher bracket = BRACKET_COMPANY.matcher(subject);
        if (bracket.find()) {
            return bracket.group(1).trim();
        }
        String address = nullToEmpty(m.fromAddress()).toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : DOMAIN_COMPANY.entrySet()) {
            if (address.endsWith("@" + e.getKey()) || address.endsWith("." + e.getKey())) {
                return e.getValue();
            }
        }
        Matcher sender = SENDER_COMPANY.matcher(fromName);
        if (sender.find()) {
            return sender.group(1).trim();
        }
        // 正文最后再看，正文里常顺带提到别的公司
        for (String c : known) {
            if (text.contains(c)) {
                return c;
            }
        }
        return null;
    }

    /**
     * 找出所有「日期 + 时间」或「日期 + 截止」，最多 3 个，按出现顺序
     */
    List<MailFinding.Slot> extractSlots(String text, Instant receivedAt, ZoneId zone) {
        LocalDate received = (receivedAt == null ? Instant.now() : receivedAt).atZone(zone).toLocalDate();
        Map<Instant, MailFinding.Slot> slots = new LinkedHashMap<>();
        List<int[]> taken = new ArrayList<>();

        Matcher full = FULL_DATE.matcher(text);
        while (full.find()) {
            LocalDate d = safeDate(Integer.parseInt(full.group(1)), Integer.parseInt(full.group(2)),
                    Integer.parseInt(full.group(3)));
            addSlot(slots, text, full.start(), full.end(), d, zone);
            taken.add(new int[]{full.start(), full.end()});
        }
        Matcher md = MONTH_DAY.matcher(text);
        while (md.find()) {
            int at = md.start();
            if (taken.stream().anyMatch(r -> at >= r[0] && at < r[1])) {
                continue;
            }
            LocalDate d = inferYear(Integer.parseInt(md.group(1)), Integer.parseInt(md.group(2)), received);
            addSlot(slots, text, md.start(), md.end(), d, zone);
        }
        Matcher en = EN_DATE.matcher(text);
        while (en.find()) {
            int month = monthOf(en.group(1));
            int day = Integer.parseInt(en.group(2));
            LocalDate d = en.group(3) != null ? safeDate(Integer.parseInt(en.group(3)), month, day)
                    : inferYear(month, day, received);
            if (d == null || en.group(4) == null) {
                continue;
            }
            int hour = Integer.parseInt(en.group(4)) % 12 + ("pm".equalsIgnoreCase(en.group(6)) ? 12 : 0);
            int minute = en.group(5) == null ? 0 : Integer.parseInt(en.group(5));
            Instant start = ZonedDateTime.of(d.atTime(hour, minute), zone).toInstant();
            slots.putIfAbsent(start, new MailFinding.Slot(start, null, false));
        }
        return slots.values().stream().limit(3).toList();
    }

    private void addSlot(Map<Instant, MailFinding.Slot> slots, String text, int dateStart, int dateEnd, LocalDate date,
                         ZoneId zone) {
        if (date == null) {
            return;
        }
        String before = text.substring(Math.max(0, dateStart - 12), dateStart);
        String after = text.substring(dateEnd);
        Matcher wd = WEEKDAY.matcher(after);
        if (wd.find()) {
            after = after.substring(wd.end());
        }
        boolean deadlineBefore = DEADLINE_BEFORE.matcher(before).find();
        Matcher time = TIME_AFTER_DATE.matcher(after);
        if (time.find()) {
            int startHour = hour(time.group(1), time.group(2));
            if (startHour > 23) {
                return;
            }
            LocalDateTime start = date.atTime(startHour, minute(time.group(3)));
            String rest = after.substring(time.end());
            LocalDateTime end = null;
            Matcher range = RANGE_END.matcher(rest);
            if (range.find()) {
                String period = range.group(1) != null ? range.group(1) : time.group(1);
                int endHour = hour(period, range.group(2));
                if (endHour <= 23) {
                    end = date.atTime(endHour, minute(range.group(3)));
                    if (end.isBefore(start)) {
                        end = null;
                    }
                }
                rest = rest.substring(range.end());
            }
            boolean deadline = deadlineBefore || DEADLINE_AFTER.matcher(rest).find();
            Instant s = ZonedDateTime.of(start, zone).toInstant();
            slots.putIfAbsent(s, new MailFinding.Slot(s, end == null ? null : ZonedDateTime.of(end, zone).toInstant(),
                    deadline));
        } else if (deadlineBefore || DEADLINE_AFTER.matcher(after).find()) {
            // 只有日期的截止时间按当天 23:59 算
            Instant s = ZonedDateTime.of(date.atTime(23, 59), zone).toInstant();
            slots.putIfAbsent(s, new MailFinding.Slot(s, null, true));
        }
    }

    static String extractLink(String text) {
        List<String> urls = new ArrayList<>();
        Matcher m = URL.matcher(text);
        while (m.find()) {
            String u = m.group().replaceAll("[.。;；]+$", "");
            String lower = u.toLowerCase(Locale.ROOT);
            if (lower.contains("unsubscribe") || lower.matches(".*\\.(png|jpg|jpeg|gif)(\\?.*)?$")) {
                continue;
            }
            urls.add(u);
        }
        return urls.stream()
                .filter(u -> PREFERRED_LINK_HOSTS.stream().anyMatch(h -> u.toLowerCase(Locale.ROOT).contains(h)))
                .findFirst()
                .orElse(urls.isEmpty() ? null : urls.getFirst());
    }

    static String extractLocation(String text) {
        Matcher m = LOCATION.matcher(text);
        return m.find() ? m.group(2).trim() : null;
    }

    /**
     * 正文写明时区时按写明的来，否则用配置的默认时区（北京时间）
     */
    ZoneId zoneOf(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("北京时间") || t.contains("gmt+8") || t.contains("utc+8") || t.contains("utc+08")) {
            return ZoneId.of("Asia/Shanghai");
        }
        if (t.matches("(?s).*\\b(est|edt|et|eastern time)\\b.*")) {
            return ZoneId.of("America/New_York");
        }
        if (t.matches("(?s).*\\b(pst|pdt|pt|pacific time)\\b.*")) {
            return ZoneId.of("America/Los_Angeles");
        }
        return defaultZone;
    }

    /**
     * 全角数字、冒号转半角，合并多余空白
     */
    static String normalize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '０' && c <= '９') {
                sb.append((char) (c - '０' + '0'));
            } else if (c == ' ' || c == '　') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().replaceAll("[ \\t]+", " ");
    }

    private static int hour(String period, String h) {
        int hour = Integer.parseInt(h);
        if (period != null && (period.equals("下午") || period.equals("傍晚") || period.equals("晚上")) && hour < 12) {
            hour += 12;
        }
        return hour;
    }

    private static int minute(String m) {
        if (m == null || m.isEmpty()) {
            return 0;
        }
        return m.equals("半") ? 30 : Math.min(59, Integer.parseInt(m));
    }

    /**
     * 没写年份时用收信年份；如果算出来比收信日期早一周以上，说明是明年（12 月收到 1 月的面试）
     */
    private static LocalDate inferYear(int month, int day, LocalDate received) {
        LocalDate d = safeDate(received.getYear(), month, day);
        if (d != null && d.isBefore(received.minusDays(7))) {
            d = safeDate(received.getYear() + 1, month, day);
        }
        return d;
    }

    private static LocalDate safeDate(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int monthOf(String s) {
        String m = s.toLowerCase(Locale.ROOT).substring(0, 3);
        return List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
                .indexOf(m) + 1;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
