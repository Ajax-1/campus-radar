package io.github.ajax1.campusradar.snapshot;

import io.github.ajax1.campusradar.collect.CollectException;
import io.github.ajax1.campusradar.collect.Json;
import io.github.ajax1.campusradar.collect.RawJob;
import io.github.ajax1.campusradar.job.JobPosting;
import io.github.ajax1.campusradar.job.RecruitType;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 快照的 JSON 结构。只有公开的岗位信息，档案、台账、日程、邮件都在别的表里，不会进来。
 */
final class SnapshotFormat {

    static final int VERSION = 1;

    private SnapshotFormat() {
    }

    static String write(List<JobPosting> jobs, Instant generatedAt) {
        List<Map<String, Object>> rows = new ArrayList<>(jobs.size());
        for (JobPosting j : jobs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("source", j.getSource());
            row.put("externalId", j.getExternalId());
            row.put("title", j.getTitle());
            row.put("category", j.getCategory());
            row.put("recruitType", j.getRecruitType().name());
            row.put("gradYear", j.getGradYear());
            row.put("cities", cities(j.getCities()));
            row.put("description", j.getDescription());
            row.put("requirement", j.getRequirement());
            row.put("applyUrl", j.getApplyUrl());
            row.put("publishedAt", j.getPublishedAt() == null ? null : j.getPublishedAt().toString());
            rows.add(row);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        root.put("generatedAt", generatedAt.toString());
        root.put("jobs", rows);
        return Json.write(root);
    }

    /**
     * @return 按数据源分组的岗位
     */
    static Map<String, List<RawJob>> parse(String json) {
        JsonNode root = Json.parse(json);
        if (root.path("version").asInt(-1) != VERSION) {
            throw new CollectException("快照版本不支持: " + root.path("version") + "，请升级到最新版本");
        }
        Map<String, List<RawJob>> bySource = new HashMap<>();
        for (JsonNode j : root.path("jobs")) {
            String published = Json.text(j, "publishedAt");
            List<String> cities = new ArrayList<>();
            j.path("cities").forEach(c -> cities.add(c.asString()));
            JsonNode year = j.path("gradYear");
            RawJob job = new RawJob(Json.text(j, "externalId"), Json.text(j, "title"), Json.text(j, "category"),
                    RecruitType.valueOf(Json.text(j, "recruitType")), year.isNumber() ? year.asInt() : null, cities,
                    Json.text(j, "description"), Json.text(j, "requirement"), Json.text(j, "applyUrl"),
                    published == null ? null : Instant.parse(published));
            bySource.computeIfAbsent(Json.text(j, "source"), k -> new ArrayList<>()).add(job);
        }
        return bySource;
    }

    private static List<String> cities(String joined) {
        return joined == null ? List.of() : Arrays.stream(joined.split(",")).filter(s -> !s.isBlank()).toList();
    }
}
