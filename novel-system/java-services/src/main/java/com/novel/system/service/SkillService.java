package com.novel.system.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.novel.system.dto.response.SkillResponse;
import com.novel.system.entity.SkillProfile;
import com.novel.system.exception.ResourceNotFoundException;
import com.novel.system.repository.SkillProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SkillService {

    private static final int SKILL_DIFF_LINE_LIMIT = 2000;
    private static final Pattern SAFE_SKILL_NAME = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final Pattern SAFE_VERSION_ID = Pattern.compile("^[A-Za-z0-9_.-]+$");
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("\\A---\\s*\\R(.*?)\\R---\\s*\\R", Pattern.DOTALL);
    private static final Pattern FIRST_HEADING_PATTERN = Pattern.compile("(?m)^#\\s+(.+?)\\s*$");
    private static final Pattern TEMPLATE_PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{[^}]+}}|____+");
    private static final Pattern SAMPLE_LINE_PATTERN = Pattern.compile("(?m)^\\s*-\\s*《?([^《》\\n（(]+)》?[（(].*?$");
    private static final Pattern FREQUENCY_LINE_PATTERN = Pattern.compile("(?m)^.*(?:出现率|期望频率|占比|percentage)[:：]?\\s*([0-9]+(?:\\.[0-9]+)?)%?.*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXAMPLE_BLOCK_PATTERN = Pattern.compile("(?s)(?:示例|示例片段|修改示例)[:：]?\\s*```\\s*(.*?)\\s*```");
    private static final DateTimeFormatter SNAPSHOT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final ProjectService projectService;
    private final SkillProfileRepository skillProfileRepository;
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @Value("${file.storage.base-path:/workspace}")
    private String basePath;

    public List<SkillResponse> listSkills(String projectId) {
        projectService.getProject(projectId);

        Path localSkillsDir = localSkillsDir(projectId);
        if (!Files.exists(localSkillsDir)) {
            return Collections.emptyList();
        }

        Map<String, Map<String, Object>> enabledConfig = readEnabledConfig(projectId);
        List<SkillResponse> skills = new ArrayList<>();

        try (var stream = Files.list(localSkillsDir)) {
            stream
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".md"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .forEach(path -> skills.add(toResponse(projectId, path, enabledConfig, false)));
        } catch (IOException e) {
            throw new RuntimeException("读取项目Skill列表失败", e);
        }

        return skills;
    }

    public SkillResponse getSkill(String projectId, String skillName) {
        projectService.getProject(projectId);
        validateSkillName(skillName);

        Path skillFile = resolveSkillFile(projectId, skillName);

        return toResponse(projectId, skillFile, readEnabledConfig(projectId), true);
    }

    public SkillResponse updateSkill(String projectId, String skillName, Map<String, Object> request) {
        projectService.getProject(projectId);
        validateSkillName(skillName);
        Path skillFile = resolveSkillFile(projectId, skillName);
        Map<String, Object> options = request == null ? Map.of() : request;
        String content = asString(options.get("content"), null);
        if (content == null) {
            throw new IllegalArgumentException("content 不能为空");
        }

        Path snapshotPath = archiveSkillFile(projectId, skillFile, "before_skill_edit");
        writeText(skillFile, content);

        String editor = asString(options.get("editor"), "human");
        String editNote = asString(options.get("editNote"), asString(options.get("edit_note"), ""));
        writeSkillReport(
            projectId,
            skillName,
            "skill_manual_edit",
            Map.of(
                "editor", editor,
                "edit_note", editNote,
                "skill_path", relative(projectId, skillFile),
                "snapshot_path", relative(projectId, snapshotPath),
                "size_before", snapshotSize(snapshotPath),
                "size_after", fileSize(skillFile)
            )
        );

        syncProjectSkillProfile(projectId);
        return getSkill(projectId, skillName);
    }

    public SkillResponse updateSkillConfig(String projectId, String skillName, Map<String, Object> request) {
        projectService.getProject(projectId);
        validateSkillName(skillName);
        Path skillFile = resolveSkillFile(projectId, skillName);
        Map<String, Object> options = request == null ? Map.of() : request;
        Path enabledFile = enabledConfigFile(projectId);
        Path snapshotPath = Files.exists(enabledFile)
            ? archiveSkillConfig(projectId, enabledFile, "before_skill_config_edit")
            : null;

        Map<String, Object> config = mutableEnabledConfig(projectId);
        List<Map<String, Object>> skills = mutableSkillEntries(config);
        Map<String, Object> entry = findOrCreateSkillEntry(projectId, skillName, skillFile, skills);

        if (options.containsKey("enabled")) {
            entry.put("enabled", asBooleanFlexible(options.get("enabled"), true));
        }
        if (options.containsKey("priority")) {
            entry.put("priority", asInteger(options.get("priority"), asInteger(entry.get("priority"), defaultPriority(asString(entry.get("type"), inferType(skillName))))));
        }
        if (options.containsKey("type")) {
            entry.put("type", asString(options.get("type"), inferType(skillName)));
        }
        if (options.containsKey("scope")) {
            entry.put("scope", asStringList(options.get("scope")));
        }
        entry.put("path", relative(projectId, skillFile));
        config.put("updated_at", LocalDateTime.now().toString());
        writeYaml(enabledFile, config);
        syncProjectSkillProfile(projectId);

        String editor = asString(options.get("editor"), "human");
        String editNote = asString(options.get("editNote"), asString(options.get("edit_note"), ""));
        writeSkillReport(
            projectId,
            skillName,
            "skill_config_edit",
            Map.of(
                "editor", editor,
                "edit_note", editNote,
                "enabled", entry.getOrDefault("enabled", true),
                "priority", entry.getOrDefault("priority", defaultPriority(asString(entry.get("type"), inferType(skillName)))),
                "type", entry.getOrDefault("type", inferType(skillName)),
                "scope", entry.getOrDefault("scope", List.of()),
                "snapshot_path", snapshotPath != null ? relative(projectId, snapshotPath) : ""
            )
        );

        return getSkill(projectId, skillName);
    }

    public Map<String, Object> detectConflicts(String projectId) {
        projectService.getProject(projectId);
        List<SkillResponse> skills = listSkills(projectId);
        List<Map<String, Object>> conflicts = detectSkillConflicts(projectId, skills);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("projectId", projectId);
        response.put("conflictCount", conflicts.size());
        response.put("conflicts", conflicts);
        response.put("checkedAt", LocalDateTime.now().toString());
        skillProfileRepository.findByProjectIdAndName(projectId, "default")
            .map(SkillProfile::getSkillMetadata)
            .ifPresent(metadata -> {
                Map<String, Object> latestReport = asMap(metadata.get("latestConflictReport"));
                if (!latestReport.isEmpty()) {
                    response.put("latestConflictReport", latestReport);
                    response.put("latestConflictReportPath", latestReport.getOrDefault("path", ""));
                }
                Map<String, Object> latestResolution = asMap(metadata.get("latestConflictResolution"));
                if (!latestResolution.isEmpty()) {
                    response.put("latestConflictResolution", latestResolution);
                    response.put("latestConflictResolutionPath", latestResolution.getOrDefault("path", ""));
                }
            });
        return response;
    }

    public Map<String, Object> generateConflictReport(String projectId, Map<String, Object> request) {
        projectService.getProject(projectId);
        Map<String, Object> options = request == null ? Map.of() : request;
        String checkedBy = asString(options.get("checkedBy"), asString(options.get("checker"), "system"));
        String checkedAt = LocalDateTime.now().toString();
        List<SkillResponse> skills = listSkills(projectId);
        List<Map<String, Object>> conflicts = detectSkillConflicts(projectId, skills);
        Map<String, Object> severityCounts = conflictSeverityCounts(conflicts);

        Map<String, Object> reportDetails = new LinkedHashMap<>();
        reportDetails.put("project_id", projectId);
        reportDetails.put("checked_by", checkedBy);
        reportDetails.put("checked_at", checkedAt);
        reportDetails.put("skill_count", skills.size());
        reportDetails.put("enabled_count", skills.stream().filter(skill -> Boolean.TRUE.equals(skill.getEnabled())).count());
        reportDetails.put("conflict_count", conflicts.size());
        reportDetails.put("severity_counts", severityCounts);
        reportDetails.put("conflicts", conflicts);
        Path reportPath = writeSkillReport(projectId, "conflicts", "skill_conflict_report", reportDetails);
        String relativeReportPath = relative(projectId, reportPath);

        SkillProfile profile = syncProjectSkillProfile(projectId);
        Map<String, Object> metadata = new LinkedHashMap<>(
            profile.getSkillMetadata() != null ? profile.getSkillMetadata() : Map.of()
        );
        Map<String, Object> latestReport = new LinkedHashMap<>();
        latestReport.put("path", relativeReportPath);
        latestReport.put("checkedBy", checkedBy);
        latestReport.put("checkedAt", checkedAt);
        latestReport.put("conflictCount", conflicts.size());
        latestReport.put("severityCounts", severityCounts);
        metadata.put("latestConflictReport", latestReport);
        metadata.put("latestConflictReportPath", relativeReportPath);
        metadata.put("conflictCount", conflicts.size());
        profile.setSkillMetadata(metadata);
        profile.setUpdatedAt(LocalDateTime.now());
        skillProfileRepository.save(profile);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("projectId", projectId);
        response.put("conflictCount", conflicts.size());
        response.put("severityCounts", severityCounts);
        response.put("conflicts", conflicts);
        response.put("reportPath", relativeReportPath);
        response.put("checkedBy", checkedBy);
        response.put("checkedAt", checkedAt);
        response.put("skillProfile", toSkillProfileMap(profile));
        return response;
    }

    public Map<String, Object> resolveConflicts(String projectId, Map<String, Object> request) {
        projectService.getProject(projectId);
        Map<String, Object> options = request == null ? Map.of() : request;
        String resolvedBy = asString(options.get("resolvedBy"), asString(options.get("operator"), "system"));
        boolean resolveScopeOverlap = asBooleanFlexible(options.get("resolveScopeOverlap"), true);
        String resolvedAt = LocalDateTime.now().toString();

        List<SkillResponse> beforeSkills = listSkills(projectId);
        List<Map<String, Object>> beforeConflicts = detectSkillConflicts(projectId, beforeSkills);
        Map<String, Object> config = mutableEnabledConfig(projectId);
        List<Map<String, Object>> entries = mutableSkillEntries(config);
        Map<String, Map<String, Object>> entriesByName = skillEntriesByName(entries);
        List<Map<String, Object>> appliedActions = new ArrayList<>();
        List<Map<String, Object>> skippedActions = new ArrayList<>();

        for (Map<String, Object> conflict : beforeConflicts) {
            String type = asString(conflict.get("type"), "");
            if ("priority_tie".equals(type)) {
                resolvePriorityTie(conflict, entries, entriesByName, appliedActions, skippedActions);
            }
        }

        for (Map<String, Object> conflict : beforeConflicts) {
            String type = asString(conflict.get("type"), "");
            if ("scope_overlap".equals(type)) {
                resolveScopeOverlap(conflict, entriesByName, resolveScopeOverlap, appliedActions, skippedActions);
            } else if (!"priority_tie".equals(type)) {
                skippedActions.add(conflictResolutionSkip(conflict, "manual_required"));
            }
        }

        Path enabledFile = enabledConfigFile(projectId);
        Path snapshotPath = null;
        if (!appliedActions.isEmpty()) {
            snapshotPath = Files.exists(enabledFile)
                ? archiveSkillConfig(projectId, enabledFile, "before_skill_conflict_resolution")
                : null;
            config.put("updated_at", LocalDateTime.now().toString());
            writeYaml(enabledFile, config);
        }

        List<SkillResponse> afterSkills = listSkills(projectId);
        List<Map<String, Object>> afterConflicts = detectSkillConflicts(projectId, afterSkills);
        Map<String, Object> severityCounts = conflictSeverityCounts(afterConflicts);

        Map<String, Object> reportDetails = new LinkedHashMap<>();
        reportDetails.put("project_id", projectId);
        reportDetails.put("resolved_by", resolvedBy);
        reportDetails.put("resolved_at", resolvedAt);
        reportDetails.put("auto_scope_overlap_enabled", resolveScopeOverlap);
        reportDetails.put("before_conflict_count", beforeConflicts.size());
        reportDetails.put("after_conflict_count", afterConflicts.size());
        reportDetails.put("applied_count", appliedActions.size());
        reportDetails.put("skipped_count", skippedActions.size());
        reportDetails.put("config_snapshot_path", snapshotPath != null ? relative(projectId, snapshotPath) : "");
        reportDetails.put("applied_actions", appliedActions);
        reportDetails.put("skipped_actions", skippedActions);
        reportDetails.put("before_conflicts", beforeConflicts);
        reportDetails.put("after_conflicts", afterConflicts);
        reportDetails.put("severity_counts", severityCounts);
        Path reportPath = writeSkillReport(projectId, "conflicts", "skill_conflict_resolution", reportDetails);
        String relativeReportPath = relative(projectId, reportPath);

        SkillProfile profile = syncProjectSkillProfile(projectId);
        Map<String, Object> metadata = new LinkedHashMap<>(
            profile.getSkillMetadata() != null ? profile.getSkillMetadata() : Map.of()
        );
        Map<String, Object> latestResolution = new LinkedHashMap<>();
        latestResolution.put("path", relativeReportPath);
        latestResolution.put("resolvedBy", resolvedBy);
        latestResolution.put("resolvedAt", resolvedAt);
        latestResolution.put("appliedCount", appliedActions.size());
        latestResolution.put("skippedCount", skippedActions.size());
        latestResolution.put("beforeConflictCount", beforeConflicts.size());
        latestResolution.put("afterConflictCount", afterConflicts.size());
        latestResolution.put("configSnapshotPath", snapshotPath != null ? relative(projectId, snapshotPath) : "");
        metadata.put("latestConflictResolution", latestResolution);
        metadata.put("latestConflictResolutionPath", relativeReportPath);
        metadata.put("conflictCount", afterConflicts.size());
        profile.setSkillMetadata(metadata);
        profile.setUpdatedAt(LocalDateTime.now());
        skillProfileRepository.save(profile);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("projectId", projectId);
        response.put("beforeConflictCount", beforeConflicts.size());
        response.put("afterConflictCount", afterConflicts.size());
        response.put("conflictCount", afterConflicts.size());
        response.put("severityCounts", severityCounts);
        response.put("appliedCount", appliedActions.size());
        response.put("skippedCount", skippedActions.size());
        response.put("appliedActions", appliedActions);
        response.put("skippedActions", skippedActions);
        response.put("beforeConflicts", beforeConflicts);
        response.put("conflicts", afterConflicts);
        response.put("reportPath", relativeReportPath);
        response.put("configSnapshotPath", snapshotPath != null ? relative(projectId, snapshotPath) : null);
        response.put("resolvedBy", resolvedBy);
        response.put("resolvedAt", resolvedAt);
        response.put("skillProfile", toSkillProfileMap(profile));
        return response;
    }

    public Map<String, Object> validateSkillQuality(String projectId, String skillName, Map<String, Object> request) {
        projectService.getProject(projectId);
        validateSkillName(skillName);
        Path skillFile = resolveSkillFile(projectId, skillName);
        Map<String, Object> options = request == null ? Map.of() : request;
        String checkedBy = asString(options.get("checkedBy"), asString(options.get("checker"), "system"));
        String content;
        try {
            content = Files.readString(skillFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("读取Skill文件失败: " + skillName, e);
        }

        Map<String, Object> frontmatter = extractFrontmatter(content);
        Map<String, Object> config = mutableEnabledConfig(projectId);
        List<Map<String, Object>> entries = mutableSkillEntries(config);
        Map<String, Object> entry = findOrCreateSkillEntry(projectId, skillName, skillFile, entries);
        String type = asString(entry.get("type"), inferType(skillName));
        List<Map<String, Object>> checks = buildSkillQualityChecks(projectId, skillName, type, content, frontmatter);
        List<Map<String, Object>> sourceTrace = buildSourceTrace(projectId, skillName, content, frontmatter, entry);
        List<Map<String, Object>> evidenceItems = buildEvidenceItems(projectId, skillName, content, frontmatter);
        Map<String, Object> semanticQuality = buildSemanticQuality(type, content, sourceTrace, evidenceItems, checks);
        int score = calculateQualityScore(checks);
        String status = score >= 80 ? "passed" : score >= 60 ? "needs_review" : "failed";
        long failedRequiredCount = checks.stream()
            .filter(check -> Boolean.TRUE.equals(check.get("required")) && !Boolean.TRUE.equals(check.get("passed")))
            .count();
        if (failedRequiredCount > 0 && score >= 80) {
            status = "needs_review";
        }

        String checkedAt = LocalDateTime.now().toString();
        Map<String, Object> reportDetails = new LinkedHashMap<>();
        reportDetails.put("checked_by", checkedBy);
        reportDetails.put("checked_at", checkedAt);
        reportDetails.put("skill_path", relative(projectId, skillFile));
        reportDetails.put("type", type);
        reportDetails.put("status", status);
        reportDetails.put("score", score);
        reportDetails.put("checks", checks);
        reportDetails.put("semantic_quality", semanticQuality);
        reportDetails.put("failed_required_count", failedRequiredCount);
        reportDetails.put("content_size", fileSize(skillFile));
        Path reportPath = writeSkillReport(projectId, skillName, "skill_quality_check", reportDetails);

        Path enabledFile = enabledConfigFile(projectId);
        Path snapshotPath = Files.exists(enabledFile)
            ? archiveSkillConfig(projectId, enabledFile, "before_skill_quality_check")
            : null;
        entry.put("quality_status", status);
        entry.put("quality_score", score);
        entry.put("quality_checked_at", checkedAt);
        entry.put("latest_quality_report_path", relative(projectId, reportPath));
        entry.put("semantic_quality", semanticQuality);
        entry.putIfAbsent("approval_status", "pending");
        config.put("updated_at", LocalDateTime.now().toString());
        writeYaml(enabledFile, config);
        syncProjectSkillProfile(projectId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("skill", getSkill(projectId, skillName));
        response.put("status", status);
        response.put("score", score);
        response.put("checks", checks);
        response.put("semanticQuality", semanticQuality);
        response.put("failedRequiredCount", failedRequiredCount);
        response.put("reportPath", relative(projectId, reportPath));
        response.put("configSnapshotPath", snapshotPath != null ? relative(projectId, snapshotPath) : null);
        response.put("checkedAt", checkedAt);
        return response;
    }

    public Map<String, Object> approveSkill(String projectId, String skillName, Map<String, Object> request) {
        return updateSkillApproval(projectId, skillName, request, "approved");
    }

    public Map<String, Object> rejectSkill(String projectId, String skillName, Map<String, Object> request) {
        return updateSkillApproval(projectId, skillName, request, "rejected");
    }

    public List<Map<String, Object>> listSkillVersions(String projectId, String skillName) {
        projectService.getProject(projectId);
        validateSkillName(skillName);
        resolveSkillFile(projectId, skillName);
        Path versionsDir = skillVersionsDir(projectId, skillName);
        if (!Files.exists(versionsDir)) {
            return List.of();
        }

        try (var stream = Files.list(versionsDir)) {
            return stream
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".md"))
                .sorted(Comparator.comparing(this::modifiedAt).reversed())
                .map(path -> skillVersionItem(projectId, skillName, path, false))
                .toList();
        } catch (IOException e) {
            throw new RuntimeException("读取Skill版本目录失败", e);
        }
    }

    public Map<String, Object> getSkillVersion(String projectId, String skillName, String versionId) {
        projectService.getProject(projectId);
        validateSkillName(skillName);
        resolveSkillFile(projectId, skillName);
        Path versionFile = resolveSkillVersionFile(projectId, skillName, versionId);
        return skillVersionItem(projectId, skillName, versionFile, true);
    }

    public Map<String, Object> diffSkillVersionWithCurrent(String projectId, String skillName, String versionId) {
        projectService.getProject(projectId);
        validateSkillName(skillName);
        Path skillFile = resolveSkillFile(projectId, skillName);
        Path versionFile = resolveSkillVersionFile(projectId, skillName, versionId);
        try {
            String versionContent = Files.readString(versionFile, StandardCharsets.UTF_8);
            String currentContent = Files.readString(skillFile, StandardCharsets.UTF_8);
            List<String> versionLines = limitedLines(versionContent, SKILL_DIFF_LINE_LIMIT);
            List<String> currentLines = limitedLines(currentContent, SKILL_DIFF_LINE_LIMIT);
            List<Map<String, Object>> diff = lineDiff(versionLines, currentLines);
            long added = diff.stream().filter(item -> "added".equals(item.get("type"))).count();
            long removed = diff.stream().filter(item -> "removed".equals(item.get("type"))).count();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("skillName", skillName);
            response.put("versionId", versionId);
            response.put("left", skillVersionItem(projectId, skillName, versionFile, false));
            response.put("right", skillCurrentItem(projectId, skillFile));
            response.put("leftLabel", "version:" + versionId);
            response.put("rightLabel", "current");
            response.put("lineLimit", SKILL_DIFF_LINE_LIMIT);
            response.put("leftTruncated", countLines(versionContent) > SKILL_DIFF_LINE_LIMIT);
            response.put("rightTruncated", countLines(currentContent) > SKILL_DIFF_LINE_LIMIT);
            response.put("addedLines", added);
            response.put("removedLines", removed);
            response.put("changedLines", added + removed);
            response.put("diff", diff);
            response.put("comparedAt", LocalDateTime.now().toString());
            return response;
        } catch (IOException e) {
            throw new RuntimeException("对比Skill版本失败: " + versionId, e);
        }
    }

    public Map<String, Object> restoreSkillVersion(
            String projectId,
            String skillName,
            String versionId,
            Map<String, Object> request) {
        projectService.getProject(projectId);
        validateSkillName(skillName);
        Path skillFile = resolveSkillFile(projectId, skillName);
        Path versionFile = resolveSkillVersionFile(projectId, skillName, versionId);
        Map<String, Object> options = request == null ? Map.of() : request;

        Path previousSnapshotPath = null;
        if (Files.exists(skillFile) && asBooleanFlexible(options.get("createVersionSnapshot"), true)) {
            previousSnapshotPath = archiveSkillFile(projectId, skillFile, "before_skill_restore");
        }

        try {
            Files.copy(versionFile, skillFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("恢复Skill版本失败: " + versionId, e);
        }
        syncProjectSkillProfile(projectId);

        String restoredAt = LocalDateTime.now().toString();
        String restorer = asString(options.get("restorer"), asString(options.get("editor"), "human"));
        String note = asString(options.get("note"), asString(options.get("editNote"), ""));
        Path reportPath = writeSkillReport(
            projectId,
            skillName,
            "skill_restore",
            Map.of(
                "restorer", restorer,
                "note", note,
                "skill_path", relative(projectId, skillFile),
                "restored_from_version_id", versionId,
                "restored_from_path", relative(projectId, versionFile),
                "previous_snapshot_path", previousSnapshotPath != null ? relative(projectId, previousSnapshotPath) : "",
                "size_after", fileSize(skillFile),
                "restored_at", restoredAt
            )
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("skill", getSkill(projectId, skillName));
        response.put("restoredPath", relative(projectId, skillFile));
        response.put("restoredFromVersionId", versionId);
        response.put("restoredFromPath", relative(projectId, versionFile));
        response.put("previousSnapshotPath", previousSnapshotPath != null ? relative(projectId, previousSnapshotPath) : null);
        response.put("reportPath", relative(projectId, reportPath));
        response.put("restoredAt", restoredAt);
        return response;
    }

    public List<Map<String, Object>> listSkillConfigVersions(String projectId) {
        projectService.getProject(projectId);
        Path versionsDir = projectRoot(projectId).resolve("skills").resolve("versions").resolve("enabled").normalize();
        if (!Files.exists(versionsDir)) {
            return List.of();
        }

        try (var stream = Files.list(versionsDir)) {
            return stream
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".yaml"))
                .sorted(Comparator.comparing(this::modifiedAt).reversed())
                .map(path -> skillConfigVersionItem(projectId, path, false))
                .toList();
        } catch (IOException e) {
            throw new RuntimeException("读取Skill配置版本目录失败", e);
        }
    }

    public Map<String, Object> restoreSkillConfigVersion(
            String projectId,
            String versionId,
            Map<String, Object> request) {
        projectService.getProject(projectId);
        Path enabledFile = enabledConfigFile(projectId);
        Path versionFile = resolveSkillConfigVersionFile(projectId, versionId);
        Map<String, Object> options = request == null ? Map.of() : request;

        Path previousSnapshotPath = null;
        if (Files.exists(enabledFile) && asBooleanFlexible(options.get("createVersionSnapshot"), true)) {
            previousSnapshotPath = archiveSkillConfig(projectId, enabledFile, "before_skill_config_restore");
        }

        try {
            Files.createDirectories(enabledFile.getParent());
            Files.copy(versionFile, enabledFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("恢复Skill配置版本失败: " + versionId, e);
        }
        syncProjectSkillProfile(projectId);

        String restoredAt = LocalDateTime.now().toString();
        Path reportPath = writeSkillReport(
            projectId,
            "enabled",
            "skill_config_restore",
            Map.of(
                "restorer", asString(options.get("restorer"), asString(options.get("editor"), "human")),
                "note", asString(options.get("note"), asString(options.get("editNote"), "")),
                "config_path", relative(projectId, enabledFile),
                "restored_from_version_id", versionId,
                "restored_from_path", relative(projectId, versionFile),
                "previous_snapshot_path", previousSnapshotPath != null ? relative(projectId, previousSnapshotPath) : "",
                "restored_at", restoredAt
            )
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("enabledConfig", getEnabledConfig(projectId));
        response.put("restoredPath", relative(projectId, enabledFile));
        response.put("restoredFromVersionId", versionId);
        response.put("restoredFromPath", relative(projectId, versionFile));
        response.put("previousSnapshotPath", previousSnapshotPath != null ? relative(projectId, previousSnapshotPath) : null);
        response.put("reportPath", relative(projectId, reportPath));
        response.put("restoredAt", restoredAt);
        return response;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getEnabledConfig(String projectId) {
        projectService.getProject(projectId);
        Path enabledFile = projectRoot(projectId).resolve("skills").resolve("enabled.yaml");
        if (!Files.exists(enabledFile)) {
            return Map.of(
                "version", "1.0.0",
                "project_id", projectId,
                "skills", List.of()
            );
        }

        try {
            return yamlMapper.readValue(enabledFile.toFile(), Map.class);
        } catch (IOException e) {
            throw new RuntimeException("读取Skill启用配置失败", e);
        }
    }

    public Map<String, Object> getSkillProfile(String projectId) {
        projectService.getProject(projectId);
        SkillProfile profile = skillProfileRepository.findByProjectIdAndName(projectId, "default")
            .orElseGet(() -> syncProjectSkillProfile(projectId));
        return toSkillProfileMap(profile);
    }

    public Map<String, Object> syncSkillProfile(String projectId) {
        projectService.getProject(projectId);
        return toSkillProfileMap(syncProjectSkillProfile(projectId));
    }

    private SkillProfile syncProjectSkillProfile(String projectId) {
        List<SkillResponse> skills = listSkills(projectId).stream()
            .map(skill -> getSkill(projectId, skill.getName()))
            .toList();
        Map<String, Object> enabledConfig = getEnabledConfig(projectId);
        List<Map<String, Object>> enabledSkills = skills.stream()
            .map(this::skillResponseToProfileEntry)
            .toList();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "workspace");
        metadata.put("storage", "database-snapshot");
        metadata.put("skillCount", skills.size());
        metadata.put("enabledCount", skills.stream().filter(skill -> Boolean.TRUE.equals(skill.getEnabled())).count());
        metadata.put("qualityCheckedCount", skills.stream().filter(skill -> skill.getQualityScore() != null).count());
        metadata.put("approvedCount", skills.stream().filter(skill -> "approved".equals(skill.getApprovalStatus())).count());
        metadata.put("conflictCount", detectSkillConflicts(projectId, skills).size());
        metadata.put("enabledConfigPath", Files.exists(enabledConfigFile(projectId)) ? "skills/enabled.yaml" : "");
        metadata.put("enabledConfig", enabledConfig);
        metadata.put("syncedAt", LocalDateTime.now().toString());

        SkillProfile profile = skillProfileRepository.findByProjectIdAndName(projectId, "default")
            .orElseGet(() -> {
                SkillProfile created = new SkillProfile();
                created.setId(projectId + ":default");
                created.setProjectId(projectId);
                created.setName("default");
                created.setCreatedAt(LocalDateTime.now());
                return created;
            });
        profile.setDescription("Default project skill profile synchronized from skills/local and skills/enabled.yaml");
        profile.setEnabledSkills(enabledSkills);
        profile.setTaskOverrides(buildTaskOverrides(enabledSkills));
        profile.setSkillMetadata(metadata);
        profile.setUpdatedAt(LocalDateTime.now());
        return skillProfileRepository.save(profile);
    }

    private Map<String, Object> skillResponseToProfileEntry(SkillResponse skill) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", skill.getName());
        entry.put("fileName", skill.getFileName());
        entry.put("type", skill.getType());
        entry.put("title", skill.getTitle());
        entry.put("description", skill.getDescription());
        entry.put("path", skill.getPath());
        entry.put("enabled", skill.getEnabled());
        entry.put("priority", skill.getPriority());
        entry.put("scope", skill.getScope() != null ? skill.getScope() : List.of());
        entry.put("conflicts", skill.getConflicts() != null ? skill.getConflicts() : List.of());
        entry.put("sourceTrace", skill.getSourceTrace() != null ? skill.getSourceTrace() : List.of());
        entry.put("evidenceItems", skill.getEvidenceItems() != null ? skill.getEvidenceItems() : List.of());
        entry.put("qualityStatus", skill.getQualityStatus());
        entry.put("qualityScore", skill.getQualityScore());
        entry.put("qualityCheckedAt", skill.getQualityCheckedAt());
        entry.put("latestQualityReportPath", skill.getLatestQualityReportPath());
        entry.put("semanticQuality", skill.getSemanticQuality() != null ? skill.getSemanticQuality() : Map.of());
        entry.put("approvalStatus", skill.getApprovalStatus());
        entry.put("approvedAt", skill.getApprovedAt());
        entry.put("approvedBy", skill.getApprovedBy());
        entry.put("rejectedAt", skill.getRejectedAt());
        entry.put("rejectedBy", skill.getRejectedBy());
        entry.put("rejectionReason", skill.getRejectionReason());
        entry.put("latestApprovalReportPath", skill.getLatestApprovalReportPath());
        entry.put("sizeBytes", skill.getSizeBytes());
        entry.put("updatedAt", skill.getUpdatedAt() != null ? skill.getUpdatedAt().toString() : "");
        entry.put("content", skill.getContent());
        return entry;
    }

    private Map<String, Object> buildTaskOverrides(List<Map<String, Object>> enabledSkills) {
        Map<String, List<String>> byScope = new LinkedHashMap<>();
        for (Map<String, Object> skill : enabledSkills) {
            if (!Boolean.TRUE.equals(skill.get("enabled"))) {
                continue;
            }
            String name = asString(skill.get("name"), "");
            if (name.isBlank()) {
                continue;
            }
            for (String scope : asStringList(skill.get("scope"))) {
                byScope.computeIfAbsent(scope, ignored -> new ArrayList<>()).add(name);
            }
        }
        Map<String, Object> overrides = new LinkedHashMap<>();
        byScope.forEach((scope, skillNames) -> overrides.put(scope, skillNames));
        return overrides;
    }

    private Map<String, Object> toSkillProfileMap(SkillProfile profile) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", profile.getId());
        response.put("projectId", profile.getProjectId());
        response.put("name", profile.getName());
        response.put("description", profile.getDescription());
        response.put("enabledSkills", profile.getEnabledSkills() != null ? profile.getEnabledSkills() : List.of());
        response.put("taskOverrides", profile.getTaskOverrides() != null ? profile.getTaskOverrides() : Map.of());
        response.put("skillMetadata", profile.getSkillMetadata() != null ? profile.getSkillMetadata() : Map.of());
        response.put("createdAt", profile.getCreatedAt() != null ? profile.getCreatedAt().toString() : "");
        response.put("updatedAt", profile.getUpdatedAt() != null ? profile.getUpdatedAt().toString() : "");
        response.put("storage", "database");
        return response;
    }

    private SkillResponse toResponse(
            String projectId,
            Path skillFile,
            Map<String, Map<String, Object>> enabledConfig,
            boolean includeContent) {
        try {
            String content = Files.readString(skillFile, StandardCharsets.UTF_8);
            String skillName = stripExtension(skillFile.getFileName().toString());
            Map<String, Object> frontmatter = extractFrontmatter(content);
            Map<String, Object> enabled = enabledConfig.getOrDefault(skillName, Collections.emptyMap());
            String type = asString(enabled.get("type"), inferType(skillName));
            List<String> explicitConflicts = asStringList(frontmatter.getOrDefault("conflicts_with", frontmatter.get("conflictsWith")));
            List<Map<String, Object>> sourceTrace = buildSourceTrace(projectId, skillName, content, frontmatter, enabled);
            List<Map<String, Object>> evidenceItems = buildEvidenceItems(projectId, skillName, content, frontmatter);

            return SkillResponse.builder()
                .name(skillName)
                .fileName(skillFile.getFileName().toString())
                .type(type)
                .title(asString(frontmatter.get("title"), extractFirstHeading(content, skillName)))
                .description(asString(frontmatter.get("description"), ""))
                .path(projectRoot(projectId).relativize(skillFile).toString().replace("\\", "/"))
                .enabled(asBoolean(enabled.get("enabled"), true))
                .priority(asInteger(enabled.get("priority"), defaultPriority(type)))
                .scope(asStringList(enabled.get("scope")))
                .conflicts(explicitConflicts)
                .sourceTrace(sourceTrace)
                .evidenceItems(evidenceItems)
                .qualityStatus(asString(enabled.get("quality_status"), "unchecked"))
                .qualityScore(asInteger(enabled.get("quality_score"), null))
                .qualityCheckedAt(asString(enabled.get("quality_checked_at"), ""))
                .latestQualityReportPath(asString(enabled.get("latest_quality_report_path"), ""))
                .semanticQuality(asMap(enabled.get("semantic_quality")))
                .approvalStatus(asString(enabled.get("approval_status"), "pending"))
                .approvedAt(asString(enabled.get("approved_at"), ""))
                .approvedBy(asString(enabled.get("approved_by"), ""))
                .rejectedAt(asString(enabled.get("rejected_at"), ""))
                .rejectedBy(asString(enabled.get("rejected_by"), ""))
                .rejectionReason(asString(enabled.get("rejection_reason"), ""))
                .latestApprovalReportPath(asString(enabled.get("latest_approval_report_path"), ""))
                .sizeBytes(Files.size(skillFile))
                .updatedAt(LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(skillFile).toInstant(),
                    ZoneId.systemDefault()
                ))
                .content(includeContent ? content : null)
                .build();
        } catch (IOException e) {
            throw new RuntimeException("读取Skill文件失败: " + skillFile.getFileName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> readEnabledConfig(String projectId) {
        Map<String, Map<String, Object>> byName = new HashMap<>();
        Map<String, Object> config = getEnabledConfig(projectId);
        Object skillsObject = config.get("skills");
        if (!(skillsObject instanceof List<?> skills)) {
            return byName;
        }

        for (Object item : skills) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> skill = new HashMap<>();
            raw.forEach((key, value) -> skill.put(String.valueOf(key), value));
            String name = asString(skill.get("name"), "");
            if (!name.isBlank()) {
                byName.put(name, skill);
            }
        }

        return byName;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractFrontmatter(String content) {
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        if (!matcher.find()) {
            return Collections.emptyMap();
        }

        try {
            return yamlMapper.readValue(matcher.group(1), Map.class);
        } catch (IOException e) {
            log.warn("Failed to parse Skill frontmatter", e);
            return Collections.emptyMap();
        }
    }

    private String extractFirstHeading(String content, String fallback) {
        Matcher matcher = FIRST_HEADING_PATTERN.matcher(content);
        return matcher.find() ? matcher.group(1).trim() : fallback;
    }

    private void validateSkillName(String skillName) {
        if (skillName == null || !SAFE_SKILL_NAME.matcher(skillName).matches()) {
            throw new IllegalArgumentException("非法Skill名称: " + skillName);
        }
    }

    private void validateVersionId(String versionId) {
        if (versionId == null || !SAFE_VERSION_ID.matcher(versionId).matches()) {
            throw new IllegalArgumentException("非法版本ID: " + versionId);
        }
    }

    private Path resolveSkillFile(String projectId, String skillName) {
        Path skillFile = localSkillsDir(projectId).resolve(skillName + ".md").normalize();
        if (!skillFile.startsWith(localSkillsDir(projectId)) || !Files.exists(skillFile)) {
            throw new ResourceNotFoundException("Skill不存在: " + skillName);
        }
        return skillFile;
    }

    private Path projectRoot(String projectId) {
        return Paths.get(basePath, "projects", projectId).normalize();
    }

    private Path localSkillsDir(String projectId) {
        return projectRoot(projectId).resolve("skills").resolve("local").normalize();
    }

    private Path enabledConfigFile(String projectId) {
        return projectRoot(projectId).resolve("skills").resolve("enabled.yaml");
    }

    private Path skillVersionsDir(String projectId, String skillName) {
        return projectRoot(projectId).resolve("skills").resolve("versions").resolve(skillName).normalize();
    }

    private String relative(String projectId, Path path) {
        return projectRoot(projectId).relativize(path).toString().replace("\\", "/");
    }

    private Path archiveSkillFile(String projectId, Path sourceFile, String reason) {
        String skillName = stripExtension(sourceFile.getFileName().toString());
        Path snapshotFile = skillVersionsDir(projectId, skillName)
            .resolve(skillName + "_" + LocalDateTime.now().format(SNAPSHOT_TIMESTAMP) + ".md");
        try {
            Files.createDirectories(snapshotFile.getParent());
            Files.copy(sourceFile, snapshotFile);
            writeSkillReport(projectId, skillName, "skill_snapshot", Map.of(
                "reason", reason,
                "source_path", relative(projectId, sourceFile),
                "snapshot_path", relative(projectId, snapshotFile)
            ));
            return snapshotFile;
        } catch (IOException e) {
            throw new RuntimeException("归档Skill失败: " + sourceFile.getFileName(), e);
        }
    }

    private Path archiveSkillConfig(String projectId, Path sourceFile, String reason) {
        Path snapshotFile = projectRoot(projectId).resolve("skills").resolve("versions").resolve("enabled")
            .resolve("enabled_" + LocalDateTime.now().format(SNAPSHOT_TIMESTAMP) + ".yaml");
        try {
            Files.createDirectories(snapshotFile.getParent());
            Files.copy(sourceFile, snapshotFile);
            writeSkillReport(projectId, "enabled", "skill_config_snapshot", Map.of(
                "reason", reason,
                "source_path", relative(projectId, sourceFile),
                "snapshot_path", relative(projectId, snapshotFile)
            ));
            return snapshotFile;
        } catch (IOException e) {
            throw new RuntimeException("归档Skill配置失败: " + sourceFile.getFileName(), e);
        }
    }

    private Path resolveSkillVersionFile(String projectId, String skillName, String versionId) {
        validateVersionId(versionId);
        Path versionsDir = skillVersionsDir(projectId, skillName);
        Path versionFile = versionsDir.resolve(versionId + ".md").normalize();
        if (!versionFile.startsWith(versionsDir) || !Files.exists(versionFile) || !Files.isRegularFile(versionFile)) {
            throw new ResourceNotFoundException("Skill版本不存在: " + versionId);
        }
        return versionFile;
    }

    private Path resolveSkillConfigVersionFile(String projectId, String versionId) {
        validateVersionId(versionId);
        Path versionsDir = projectRoot(projectId).resolve("skills").resolve("versions").resolve("enabled").normalize();
        Path versionFile = versionsDir.resolve(versionId + ".yaml").normalize();
        if (!versionFile.startsWith(versionsDir) || !Files.exists(versionFile) || !Files.isRegularFile(versionFile)) {
            throw new ResourceNotFoundException("Skill配置版本不存在: " + versionId);
        }
        return versionFile;
    }

    private Map<String, Object> skillVersionItem(
            String projectId,
            String skillName,
            Path versionFile,
            boolean includeContent) {
        try {
            String content = Files.readString(versionFile, StandardCharsets.UTF_8);
            Map<String, Object> frontmatter = extractFrontmatter(content);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", stripSuffix(versionFile.getFileName().toString(), ".md"));
            item.put("skillName", skillName);
            item.put("path", relative(projectId, versionFile));
            item.put("title", asString(frontmatter.get("title"), extractFirstHeading(content, skillName)));
            item.put("description", asString(frontmatter.get("description"), ""));
            item.put("sizeBytes", Files.size(versionFile));
            item.put("archivedAt", modifiedAt(versionFile));
            if (includeContent) {
                item.put("content", content);
            }
            return item;
        } catch (IOException e) {
            throw new RuntimeException("读取Skill版本失败: " + versionFile.getFileName(), e);
        }
    }

    private Map<String, Object> skillCurrentItem(String projectId, Path skillFile) {
        try {
            String content = Files.readString(skillFile, StandardCharsets.UTF_8);
            String skillName = stripExtension(skillFile.getFileName().toString());
            Map<String, Object> frontmatter = extractFrontmatter(content);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", "current");
            item.put("skillName", skillName);
            item.put("path", relative(projectId, skillFile));
            item.put("title", asString(frontmatter.get("title"), extractFirstHeading(content, skillName)));
            item.put("description", asString(frontmatter.get("description"), ""));
            item.put("sizeBytes", Files.size(skillFile));
            item.put("updatedAt", modifiedAt(skillFile));
            return item;
        } catch (IOException e) {
            throw new RuntimeException("读取当前Skill失败: " + skillFile.getFileName(), e);
        }
    }

    private Map<String, Object> skillConfigVersionItem(String projectId, Path versionFile, boolean includeContent) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", stripSuffix(versionFile.getFileName().toString(), ".yaml"));
        item.put("path", relative(projectId, versionFile));
        item.put("sizeBytes", fileSize(versionFile));
        item.put("archivedAt", modifiedAt(versionFile));
        if (includeContent) {
            try {
                item.put("content", Files.readString(versionFile, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RuntimeException("读取Skill配置版本失败: " + versionFile.getFileName(), e);
            }
        }
        return item;
    }

    private List<Map<String, Object>> buildSourceTrace(
            String projectId,
            String skillName,
            String content,
            Map<String, Object> frontmatter,
            Map<String, Object> enabled) {
        List<Map<String, Object>> trace = new ArrayList<>();
        addTraceItem(trace, "skill_file", skillName, "skills/local/" + skillName + ".md", "Skill Markdown 文件");

        String generatedFrom = asString(
            frontmatter.getOrDefault("generated_from", frontmatter.get("generatedFrom")),
            ""
        );
        if (!generatedFrom.isBlank()) {
            addTraceItem(trace, "generated_from", generatedFrom, "", "frontmatter.generated_from");
        }

        for (String sample : sampleNamesFrom(frontmatter.getOrDefault("sample_books", frontmatter.get("sampleBooks")))) {
            addTraceItem(trace, "sample_book", sample, "", "frontmatter.sample_books");
        }
        for (String sample : sampleNamesFrom(frontmatter.getOrDefault("source_samples", frontmatter.get("sourceSamples")))) {
            addTraceItem(trace, "source_sample", sample, "", "frontmatter.source_samples");
        }
        for (String sample : sampleNamesFrom(enabled.get("source_samples"))) {
            addTraceItem(trace, "source_sample", sample, "", "enabled.yaml");
        }
        for (String sample : sampleNamesFrom(extractSampleSection(content))) {
            addTraceItem(trace, "sample_book", sample, "", "Skill正文样本书籍");
        }

        Path projectRoot = projectRoot(projectId);
        List<Path> analysisArtifacts = List.of(
            projectRoot.resolve("analysis").resolve("cross_book").resolve("cross_book_synthesis.md"),
            projectRoot.resolve("analysis").resolve("cross_book").resolve("technique_summary.json")
        );
        for (Path artifact : analysisArtifacts) {
            if (Files.exists(artifact)) {
                addTraceItem(trace, "analysis_artifact", artifact.getFileName().toString(), relative(projectId, artifact), "项目分析产物");
            }
        }

        return deduplicateTrace(trace);
    }

    private List<Map<String, Object>> buildEvidenceItems(
            String projectId,
            String skillName,
            String content,
            Map<String, Object> frontmatter) {
        List<Map<String, Object>> evidence = new ArrayList<>();
        Object rawEvidence = frontmatter.getOrDefault("evidence", frontmatter.get("evidence_items"));
        if (rawEvidence instanceof List<?> list) {
            int index = 1;
            for (Object item : list) {
                if (item instanceof Map<?, ?> raw) {
                    Map<String, Object> evidenceItem = new LinkedHashMap<>();
                    raw.forEach((key, value) -> evidenceItem.put(String.valueOf(key), value));
                    evidenceItem.putIfAbsent("id", "frontmatter_" + index);
                    evidenceItem.putIfAbsent("source", "frontmatter");
                    evidence.add(evidenceItem);
                } else if (item != null) {
                    evidence.add(evidenceItem("frontmatter_" + index, "frontmatter", String.valueOf(item), "", 0));
                }
                index++;
            }
        }

        Matcher frequencyMatcher = FREQUENCY_LINE_PATTERN.matcher(content);
        int frequencyIndex = 1;
        while (frequencyMatcher.find() && frequencyIndex <= 8) {
            String line = frequencyMatcher.group(0).trim();
            evidence.add(evidenceItem(
                "frequency_" + frequencyIndex,
                "frequency",
                line.length() > 180 ? line.substring(0, 180) : line,
                "skills/local/" + skillName + ".md",
                lineNumber(content, frequencyMatcher.start())
            ));
            frequencyIndex++;
        }

        Matcher exampleMatcher = EXAMPLE_BLOCK_PATTERN.matcher(content);
        int exampleIndex = 1;
        while (exampleMatcher.find() && exampleIndex <= 5) {
            String snippet = exampleMatcher.group(1).trim().replaceAll("\\s+", " ");
            if (!snippet.isBlank()) {
                evidence.add(evidenceItem(
                    "example_" + exampleIndex,
                    "example",
                    snippet.length() > 220 ? snippet.substring(0, 220) : snippet,
                    "skills/local/" + skillName + ".md",
                    lineNumber(content, exampleMatcher.start())
                ));
                exampleIndex++;
            }
        }

        if (Files.exists(projectRoot(projectId).resolve("analysis").resolve("cross_book").resolve("technique_summary.json"))) {
            evidence.add(evidenceItem(
                "technique_summary",
                "analysis_artifact",
                "引用项目 technique_summary.json 作为技巧统计来源",
                "analysis/cross_book/technique_summary.json",
                0
            ));
        }

        return evidence;
    }

    private void addTraceItem(List<Map<String, Object>> trace, String type, String name, String path, String evidence) {
        if (name == null || name.isBlank()) {
            return;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("name", name.trim());
        item.put("path", path == null ? "" : path);
        item.put("evidence", evidence == null ? "" : evidence);
        trace.add(item);
    }

    private List<Map<String, Object>> deduplicateTrace(List<Map<String, Object>> trace) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> deduped = new ArrayList<>();
        for (Map<String, Object> item : trace) {
            String key = item.get("type") + "|" + item.get("name") + "|" + item.get("path");
            if (seen.add(key)) {
                deduped.add(item);
            }
        }
        return deduped;
    }

    private List<String> sampleNamesFrom(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream()
                .map(String::valueOf)
                .map(String::trim)
                .filter(text -> !text.isBlank())
                .toList();
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return List.of();
        }
        String[] parts = text.split("[,，、;；\\n]+");
        List<String> names = new ArrayList<>();
        for (String part : parts) {
            String name = part.replace("《", "").replace("》", "").trim();
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    private List<String> extractSampleSection(String content) {
        List<String> samples = new ArrayList<>();
        Matcher matcher = SAMPLE_LINE_PATTERN.matcher(content);
        while (matcher.find() && samples.size() < 12) {
            String sample = matcher.group(1).trim();
            if (!sample.isBlank()) {
                samples.add(sample);
            }
        }
        return samples;
    }

    private Map<String, Object> evidenceItem(String id, String type, String text, String path, int line) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("type", type);
        item.put("text", text);
        item.put("path", path);
        if (line > 0) {
            item.put("line", line);
        }
        return item;
    }

    private int lineNumber(String content, int offset) {
        int line = 1;
        int limit = Math.min(offset, content.length());
        for (int index = 0; index < limit; index++) {
            if (content.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }

    private void writeText(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("写入Skill文件失败: " + file.getFileName(), e);
        }
    }

    private void writeYaml(Path file, Map<String, Object> data) {
        try {
            Files.createDirectories(file.getParent());
            yamlMapper.writeValue(file.toFile(), data);
        } catch (IOException e) {
            throw new RuntimeException("写入Skill配置失败: " + file.getFileName(), e);
        }
    }

    private Path writeSkillReport(String projectId, String skillName, String reportType, Map<String, Object> details) {
        Path reportFile = projectRoot(projectId).resolve("novel").resolve("reviews").resolve("skills")
            .resolve(skillName + "_" + reportType + "_" + LocalDateTime.now().format(SNAPSHOT_TIMESTAMP) + ".json");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("review_type", reportType);
        report.put("skill_name", skillName);
        report.put("created_at", LocalDateTime.now().toString());
        report.putAll(details);
        try {
            Files.createDirectories(reportFile.getParent());
            jsonMapper.writerWithDefaultPrettyPrinter().writeValue(reportFile.toFile(), report);
            return reportFile;
        } catch (IOException e) {
            throw new RuntimeException("写入Skill报告失败: " + reportFile.getFileName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mutableEnabledConfig(String projectId) {
        Map<String, Object> raw = new LinkedHashMap<>(getEnabledConfig(projectId));
        raw.putIfAbsent("version", "1.0.0");
        raw.put("project_id", projectId);
        raw.putIfAbsent("skills", new ArrayList<Map<String, Object>>());
        return raw;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mutableSkillEntries(Map<String, Object> config) {
        Object skillsObject = config.get("skills");
        List<Map<String, Object>> entries = new ArrayList<>();
        if (skillsObject instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> raw) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    raw.forEach((key, value) -> entry.put(String.valueOf(key), value));
                    entries.add(entry);
                }
            }
        }
        config.put("skills", entries);
        return entries;
    }

    private Map<String, Object> findOrCreateSkillEntry(
            String projectId,
            String skillName,
            Path skillFile,
            List<Map<String, Object>> entries) {
        for (Map<String, Object> entry : entries) {
            if (skillName.equals(asString(entry.get("name"), ""))) {
                return entry;
            }
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        String type = inferType(skillName);
        entry.put("name", skillName);
        entry.put("type", type);
        entry.put("path", relative(projectId, skillFile));
        entry.put("enabled", true);
        entry.put("priority", defaultPriority(type));
        entry.put("scope", defaultScope(type));
        entries.add(entry);
        return entry;
    }

    private Map<String, Map<String, Object>> skillEntriesByName(List<Map<String, Object>> entries) {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> entry : entries) {
            String name = asString(entry.get("name"), "");
            if (!name.isBlank()) {
                byName.put(name, entry);
            }
        }
        return byName;
    }

    private void resolvePriorityTie(
            Map<String, Object> conflict,
            List<Map<String, Object>> entries,
            Map<String, Map<String, Object>> entriesByName,
            List<Map<String, Object>> appliedActions,
            List<Map<String, Object>> skippedActions) {
        String skillA = asString(conflict.get("skillA"), "");
        String skillB = asString(conflict.get("skillB"), "");
        Map<String, Object> left = entriesByName.get(skillA);
        Map<String, Object> right = entriesByName.get(skillB);
        if (left == null || right == null) {
            skippedActions.add(conflictResolutionSkip(conflict, "missing_skill_config"));
            return;
        }

        String secondaryName = secondarySkillNameForPriorityTie(skillA, left, skillB, right);
        String primaryName = secondaryName.equals(skillA) ? skillB : skillA;
        Map<String, Object> primary = entriesByName.get(primaryName);
        Map<String, Object> secondary = entriesByName.get(secondaryName);
        int primaryPriority = skillPriority(primary);
        int secondaryPriority = skillPriority(secondary);
        if (primaryPriority != secondaryPriority) {
            skippedActions.add(conflictResolutionSkip(conflict, "already_resolved"));
            return;
        }

        int newPriority = nextAvailablePriority(secondaryPriority - 1, secondaryName, asStringList(secondary.get("scope")), entries);
        secondary.put("priority", newPriority);

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "priority_tie");
        action.put("action", "adjust_priority");
        action.put("skillName", secondaryName);
        action.put("preservedSkillName", primaryName);
        action.put("fromPriority", secondaryPriority);
        action.put("toPriority", newPriority);
        action.put("reason", "同 scope 同优先级会造成 Skill 路由不稳定，自动降低备用 Skill 优先级");
        action.put("conflict", conflictSummary(conflict));
        appliedActions.add(action);
    }

    private String secondarySkillNameForPriorityTie(
            String skillA,
            Map<String, Object> left,
            String skillB,
            Map<String, Object> right) {
        boolean leftLooksSecondary = looksLikeSecondarySkill(skillA);
        boolean rightLooksSecondary = looksLikeSecondarySkill(skillB);
        if (leftLooksSecondary != rightLooksSecondary) {
            return leftLooksSecondary ? skillA : skillB;
        }

        String leftType = asString(left.get("type"), inferType(skillA));
        String rightType = asString(right.get("type"), inferType(skillB));
        boolean leftIsCanonical = skillA.equals(leftType + "_skill");
        boolean rightIsCanonical = skillB.equals(rightType + "_skill");
        if (leftIsCanonical != rightIsCanonical) {
            return leftIsCanonical ? skillB : skillA;
        }

        return skillB;
    }

    private boolean looksLikeSecondarySkill(String skillName) {
        String normalized = skillName.toLowerCase(Locale.ROOT);
        return normalized.contains("backup")
            || normalized.contains("fallback")
            || normalized.contains("draft")
            || normalized.contains("candidate")
            || normalized.contains("legacy");
    }

    private void resolveScopeOverlap(
            Map<String, Object> conflict,
            Map<String, Map<String, Object>> entriesByName,
            boolean enabled,
            List<Map<String, Object>> appliedActions,
            List<Map<String, Object>> skippedActions) {
        if (!enabled) {
            skippedActions.add(conflictResolutionSkip(conflict, "scope_overlap_auto_resolution_disabled"));
            return;
        }

        String skillA = asString(conflict.get("skillA"), "");
        String skillB = asString(conflict.get("skillB"), "");
        Map<String, Object> left = entriesByName.get(skillA);
        Map<String, Object> right = entriesByName.get(skillB);
        if (left == null || right == null) {
            skippedActions.add(conflictResolutionSkip(conflict, "missing_skill_config"));
            return;
        }

        List<String> leftScope = new ArrayList<>(asStringList(left.get("scope")));
        List<String> rightScope = new ArrayList<>(asStringList(right.get("scope")));
        Set<String> overlap = new LinkedHashSet<>(leftScope);
        overlap.retainAll(rightScope);
        if (overlap.isEmpty()) {
            skippedActions.add(conflictResolutionSkip(conflict, "already_resolved"));
            return;
        }

        int leftPriority = skillPriority(left);
        int rightPriority = skillPriority(right);
        if (leftPriority == rightPriority) {
            skippedActions.add(conflictResolutionSkip(conflict, "priority_still_tied"));
            return;
        }

        Map<String, Object> lowerPriorityEntry = leftPriority < rightPriority ? left : right;
        String lowerSkillName = asString(lowerPriorityEntry.get("name"), "");
        List<String> currentScope = new ArrayList<>(asStringList(lowerPriorityEntry.get("scope")));
        List<String> narrowedScope = currentScope.stream()
            .filter(scope -> !overlap.contains(scope))
            .toList();
        if (narrowedScope.isEmpty()) {
            skippedActions.add(conflictResolutionSkip(conflict, "scope_narrowing_would_disable_skill"));
            return;
        }

        lowerPriorityEntry.put("scope", narrowedScope);

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "scope_overlap");
        action.put("action", "narrow_scope");
        action.put("skillName", lowerSkillName);
        action.put("removedScope", new ArrayList<>(overlap));
        action.put("fromScope", currentScope);
        action.put("toScope", narrowedScope);
        action.put("reason", "低优先级 Skill 仍保留非重叠 scope，自动移除重叠路由范围");
        action.put("conflict", conflictSummary(conflict));
        appliedActions.add(action);
    }

    private int skillPriority(Map<String, Object> entry) {
        return asInteger(entry.get("priority"), defaultPriority(asString(entry.get("type"), inferType(asString(entry.get("name"), "")))));
    }

    private int nextAvailablePriority(
            int preferredPriority,
            String skillName,
            List<String> scope,
            List<Map<String, Object>> entries) {
        int candidate = preferredPriority;
        Set<String> scopeSet = new LinkedHashSet<>(scope);
        for (int attempts = 0; attempts < 1000; attempts++) {
            boolean used = false;
            for (Map<String, Object> entry : entries) {
                if (skillName.equals(asString(entry.get("name"), "")) || !asBooleanFlexible(entry.get("enabled"), true)) {
                    continue;
                }
                Set<String> otherScope = new LinkedHashSet<>(asStringList(entry.get("scope")));
                otherScope.retainAll(scopeSet);
                if (!otherScope.isEmpty() && skillPriority(entry) == candidate) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                return candidate;
            }
            candidate--;
        }
        return preferredPriority - 1000;
    }

    private Map<String, Object> conflictResolutionSkip(Map<String, Object> conflict, String reason) {
        Map<String, Object> skipped = new LinkedHashMap<>();
        skipped.put("type", asString(conflict.get("type"), ""));
        skipped.put("skillA", asString(conflict.get("skillA"), ""));
        skipped.put("skillB", asString(conflict.get("skillB"), ""));
        skipped.put("reason", reason);
        skipped.put("suggestion", conflict.getOrDefault("suggestion", ""));
        return skipped;
    }

    private Map<String, Object> conflictSummary(Map<String, Object> conflict) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("type", asString(conflict.get("type"), ""));
        summary.put("severity", asString(conflict.get("severity"), ""));
        summary.put("skillA", asString(conflict.get("skillA"), ""));
        summary.put("skillB", asString(conflict.get("skillB"), ""));
        summary.put("message", asString(conflict.get("message"), ""));
        return summary;
    }

    private List<Map<String, Object>> detectSkillConflicts(String projectId, List<SkillResponse> skills) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        Map<String, SkillResponse> byName = new HashMap<>();
        for (SkillResponse skill : skills) {
            byName.put(skill.getName(), skill);
        }

        for (SkillResponse skill : skills) {
            if (!Boolean.TRUE.equals(skill.getEnabled())) {
                continue;
            }
            for (String conflictName : skill.getConflicts()) {
                SkillResponse other = byName.get(conflictName);
                if (other != null && Boolean.TRUE.equals(other.getEnabled())) {
                    conflicts.add(conflictItem("explicit_conflict", "error", skill.getName(), other.getName(),
                        "Skill frontmatter 声明了 conflicts_with"));
                }
            }
        }

        for (int i = 0; i < skills.size(); i++) {
            SkillResponse left = skills.get(i);
            if (!Boolean.TRUE.equals(left.getEnabled())) {
                continue;
            }
            for (int j = i + 1; j < skills.size(); j++) {
                SkillResponse right = skills.get(j);
                if (!Boolean.TRUE.equals(right.getEnabled())) {
                    continue;
                }
                Set<String> overlap = new LinkedHashSet<>(left.getScope());
                overlap.retainAll(right.getScope());
                if (!overlap.isEmpty() && left.getType().equals(right.getType())) {
                    conflicts.add(conflictItem("scope_overlap", "warning", left.getName(), right.getName(),
                        "同类型启用 Skill 覆盖相同 scope: " + String.join(",", overlap)));
                }
                if (!overlap.isEmpty() && left.getPriority().equals(right.getPriority())) {
                    conflicts.add(conflictItem("priority_tie", "info", left.getName(), right.getName(),
                        "相同 scope 下优先级相同，路由结果可能不稳定: " + String.join(",", overlap)));
                }
                if (!overlap.isEmpty()) {
                    List<String> semanticConflicts = semanticRuleConflicts(
                        skillContent(projectId, left.getName()),
                        skillContent(projectId, right.getName())
                    );
                    for (String topic : semanticConflicts) {
                        conflicts.add(conflictItem("semantic_rule_conflict", "warning", left.getName(), right.getName(),
                            "同一 scope 下对「" + topic + "」存在必须/禁止式规则冲突"));
                    }
                }
            }
        }
        return conflicts;
    }

    private Map<String, Object> updateSkillApproval(
            String projectId,
            String skillName,
            Map<String, Object> request,
            String decision) {
        projectService.getProject(projectId);
        validateSkillName(skillName);
        Path skillFile = resolveSkillFile(projectId, skillName);
        Map<String, Object> options = request == null ? Map.of() : request;
        Path enabledFile = enabledConfigFile(projectId);
        Path snapshotPath = Files.exists(enabledFile)
            ? archiveSkillConfig(projectId, enabledFile, "before_skill_" + decision)
            : null;

        Map<String, Object> config = mutableEnabledConfig(projectId);
        List<Map<String, Object>> entries = mutableSkillEntries(config);
        Map<String, Object> entry = findOrCreateSkillEntry(projectId, skillName, skillFile, entries);
        String actor = asString(options.get("reviewer"), asString(options.get("approver"), asString(options.get("user"), "human")));
        String note = asString(options.get("note"), asString(options.get("reason"), ""));
        String decidedAt = LocalDateTime.now().toString();
        boolean enableAfterDecision = asBooleanFlexible(options.get("enabled"), "approved".equals(decision));

        entry.put("approval_status", decision);
        entry.put("enabled", enableAfterDecision);
        if ("approved".equals(decision)) {
            entry.put("approved_by", actor);
            entry.put("approved_at", decidedAt);
            entry.put("approval_note", note);
            entry.remove("rejected_by");
            entry.remove("rejected_at");
            entry.remove("rejection_reason");
        } else {
            entry.put("rejected_by", actor);
            entry.put("rejected_at", decidedAt);
            entry.put("rejection_reason", note);
        }

        Map<String, Object> reportDetails = new LinkedHashMap<>();
        reportDetails.put("decision", decision);
        reportDetails.put("reviewer", actor);
        reportDetails.put("note", note);
        reportDetails.put("skill_path", relative(projectId, skillFile));
        reportDetails.put("quality_status", entry.getOrDefault("quality_status", "unchecked"));
        reportDetails.put("quality_score", entry.get("quality_score"));
        reportDetails.put("enabled_after_decision", enableAfterDecision);
        reportDetails.put("config_snapshot_path", snapshotPath != null ? relative(projectId, snapshotPath) : "");
        reportDetails.put("decided_at", decidedAt);
        Path reportPath = writeSkillReport(
            projectId,
            skillName,
            "approved".equals(decision) ? "skill_approval" : "skill_rejection",
            reportDetails
        );
        entry.put("latest_approval_report_path", relative(projectId, reportPath));
        config.put("updated_at", LocalDateTime.now().toString());
        writeYaml(enabledFile, config);
        syncProjectSkillProfile(projectId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("skill", getSkill(projectId, skillName));
        response.put("decision", decision);
        response.put("reportPath", relative(projectId, reportPath));
        response.put("configSnapshotPath", snapshotPath != null ? relative(projectId, snapshotPath) : null);
        response.put("decidedAt", decidedAt);
        return response;
    }

    private List<Map<String, Object>> buildSkillQualityChecks(
            String projectId,
            String skillName,
            String type,
            String content,
            Map<String, Object> frontmatter) {
        String normalized = content.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> checks = new ArrayList<>();
        addQualityCheck(
            checks,
            "frontmatter_title",
            "包含 frontmatter 标题或一级标题",
            true,
            frontmatter.containsKey("title") || FIRST_HEADING_PATTERN.matcher(content).find(),
            10,
            "Skill 需要可读标题，方便人工管理和下游引用"
        );
        addQualityCheck(
            checks,
            "description",
            "包含描述信息",
            false,
            hasAny(frontmatter, "description") || containsAny(normalized, "项目概述", "说明", "用途"),
            5,
            "缺描述会降低可维护性"
        );
        addQualityCheck(
            checks,
            "content_length",
            "内容达到最低长度",
            true,
            content.length() >= 1200,
            10,
            "少于 1200 字符通常不足以承载样本技法、规则和约束"
        );
        addQualityCheck(
            checks,
            "source_trace",
            "包含来源样本或分析来源",
            true,
            hasAny(frontmatter, "generated_from", "sample_books", "source_samples")
                || containsAny(normalized, "样本", "sample", "分析数据", "来源"),
            10,
            "Skill 应能追溯到样本或分析结果"
        );
        addQualityCheck(
            checks,
            "scope",
            "包含适用范围或作用域",
            true,
            containsAny(normalized, "适用范围", "作用域", "应用场景", "适用场景", "scope"),
            8,
            "缺少适用范围会影响 Skill 路由和人工判断"
        );
        addQualityCheck(
            checks,
            "inputs",
            "声明输入来源",
            false,
            containsAny(normalized, "输入来源", "输入", "参照", "上下文", "project soul", "大纲"),
            7,
            "输入来源越明确，越利于 Agent 稳定拼装 prompt"
        );
        addQualityCheck(
            checks,
            "must_rules",
            "包含必须遵守的规则",
            true,
            containsAny(normalized, "必须", "检查清单", "控制要点", "规则", "要点"),
            10,
            "文档要求 Skill 明确列出必须使用的规则"
        );
        addQualityCheck(
            checks,
            "evidence",
            "包含证据引用或样本示例",
            false,
            containsAny(normalized, "证据", "引用", "示例", "出现率", "样本书籍"),
            8,
            "缺证据会削弱 Skill 的可信度和可审查性"
        );
        addQualityCheck(
            checks,
            "anti_copy_risk",
            "包含复刻风险或避免事项",
            false,
            containsAny(normalized, "复刻", "避免", "禁止", "不得", "不要"),
            8,
            "文档要求 Skill 明确控制复刻风险"
        );
        addQualityCheck(
            checks,
            "template_placeholders",
            "模板占位符已清理",
            false,
            !TEMPLATE_PLACEHOLDER_PATTERN.matcher(content).find(),
            8,
            "保留 {{placeholder}} 或 ____ 说明生成结果仍需人工补全"
        );
        addQualityCheck(
            checks,
            "conflict_risk",
            "无启用冲突风险",
            false,
            conflictCountForSkill(projectId, skillName) == 0,
            6,
            "启用冲突会导致同一 scope 下路由不稳定"
        );

        if ("writing".equals(type)) {
            addQualityCheck(
                checks,
                "writing_boundary",
                "正文 Skill 包含章节边界控制",
                true,
                containsAny(normalized, "章节边界", "边界控制", "must_not_write", "stop_point", "后续章纲"),
                10,
                "正文创作必须防止越界透支后续章纲"
            );
        } else if ("outline".equals(type)) {
            addQualityCheck(
                checks,
                "outline_boundary",
                "大纲 Skill 包含分卷/章节边界或节奏结构",
                true,
                containsAny(normalized, "章节边界", "分卷", "章节结构", "节奏", "章末"),
                10,
                "大纲 Skill 应约束章节结构、节奏和边界"
            );
        } else if ("review".equals(type)) {
            addQualityCheck(
                checks,
                "review_dimensions",
                "审查 Skill 包含审查维度",
                true,
                containsAny(normalized, "审查维度", "检查", "质量", "一致性", "连续性"),
                10,
                "审查 Skill 必须定义可执行的审查维度"
            );
        }
        return checks;
    }

    private void addQualityCheck(
            List<Map<String, Object>> checks,
            String id,
            String title,
            boolean required,
            boolean passed,
            int weight,
            String message) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("id", id);
        check.put("title", title);
        check.put("required", required);
        check.put("passed", passed);
        check.put("weight", weight);
        check.put("message", passed ? "" : message);
        checks.add(check);
    }

    private int calculateQualityScore(List<Map<String, Object>> checks) {
        int total = 0;
        int passed = 0;
        for (Map<String, Object> check : checks) {
            int weight = asInteger(check.get("weight"), 0);
            total += weight;
            if (Boolean.TRUE.equals(check.get("passed"))) {
                passed += weight;
            }
        }
        if (total == 0) {
            return 0;
        }
        return Math.round((passed * 100.0f) / total);
    }

    private Map<String, Object> buildSemanticQuality(
            String type,
            String content,
            List<Map<String, Object>> sourceTrace,
            List<Map<String, Object>> evidenceItems,
            List<Map<String, Object>> checks) {
        String normalized = content.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> dimensions = new ArrayList<>();
        addSemanticDimension(
            dimensions,
            "topic_coverage",
            "主题覆盖",
            semanticTopicScore(type, normalized),
            semanticTopicCoverage(type, normalized),
            "覆盖该 Skill 类型所需的核心语义主题"
        );
        addSemanticDimension(
            dimensions,
            "rule_specificity",
            "规则可执行性",
            ruleSpecificityScore(normalized),
            Map.of(
                "mustCount", keywordCount(normalized, "必须", "应当", "需要", "保持"),
                "avoidCount", keywordCount(normalized, "禁止", "不得", "避免", "不要"),
                "checklistCount", keywordCount(normalized, "检查清单", "控制要点", "验收", "标准")
            ),
            "规则需要可执行，而不仅是抽象风格描述"
        );
        addSemanticDimension(
            dimensions,
            "evidence_density",
            "证据密度",
            evidenceDensityScore(content, sourceTrace, evidenceItems),
            Map.of(
                "sourceTraceCount", sourceTrace.size(),
                "evidenceItemCount", evidenceItems.size(),
                "exampleCount", keywordCount(normalized, "示例", "引用", "证据"),
                "frequencyCount", keywordCount(normalized, "出现率", "占比", "percentage")
            ),
            "Skill 应可追溯到样本、分析产物或证据片段"
        );
        addSemanticDimension(
            dimensions,
            "risk_control",
            "风险控制",
            riskControlScore(normalized),
            Map.of(
                "antiCopy", containsAny(normalized, "复刻", "照搬", "抄袭", "样本文字"),
                "boundary", containsAny(normalized, "章节边界", "边界控制", "后续章纲", "不得提前"),
                "conflict", containsAny(normalized, "冲突", "矛盾", "优先级", "scope")
            ),
            "控制复刻、越界、冲突和路由不稳定风险"
        );
        addSemanticDimension(
            dimensions,
            "maintainability",
            "可维护性",
            maintainabilityScore(content, checks),
            Map.of(
                "headingCount", headingCount(content),
                "placeholderRemaining", TEMPLATE_PLACEHOLDER_PATTERN.matcher(content).find(),
                "contentSize", content.length()
            ),
            "结构清晰、无模板残留，方便人工审阅和版本维护"
        );

        int score = Math.round((float) dimensions.stream()
            .mapToInt(item -> asInteger(item.get("score"), 0))
            .average()
            .orElse(0.0));
        List<Map<String, Object>> risks = semanticRisks(dimensions, normalized);
        List<String> recommendations = semanticRecommendations(dimensions, type, normalized);

        Map<String, Object> semantic = new LinkedHashMap<>();
        semantic.put("score", score);
        semantic.put("status", score >= 80 && risks.isEmpty() ? "passed" : score >= 60 ? "needs_review" : "failed");
        semantic.put("dimensions", dimensions);
        semantic.put("risks", risks);
        semantic.put("recommendations", recommendations);
        semantic.put("checkedAt", LocalDateTime.now().toString());
        return semantic;
    }

    private void addSemanticDimension(
            List<Map<String, Object>> dimensions,
            String id,
            String title,
            int score,
            Map<String, ?> metrics,
            String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("title", title);
        item.put("score", Math.max(0, Math.min(100, score)));
        item.put("status", score >= 80 ? "passed" : score >= 60 ? "needs_review" : "failed");
        item.put("metrics", metrics);
        item.put("message", message);
        dimensions.add(item);
    }

    private int semanticTopicScore(String type, String normalized) {
        Map<String, Boolean> coverage = semanticTopicCoverage(type, normalized);
        long covered = coverage.values().stream().filter(Boolean::booleanValue).count();
        return coverage.isEmpty() ? 60 : Math.round(covered * 100.0f / coverage.size());
    }

    private Map<String, Boolean> semanticTopicCoverage(String type, String normalized) {
        Map<String, Boolean> coverage = new LinkedHashMap<>();
        if ("writing".equals(type)) {
            coverage.put("style", containsAny(normalized, "风格", "文风", "语言", "句式"));
            coverage.put("scene", containsAny(normalized, "场景", "冲突", "动作", "氛围"));
            coverage.put("pace", containsAny(normalized, "节奏", "快慢", "推进", "张弛"));
            coverage.put("boundary", containsAny(normalized, "章节边界", "边界控制", "后续章纲", "不得提前"));
            coverage.put("review", containsAny(normalized, "检查", "验收", "质量", "修订"));
        } else if ("outline".equals(type)) {
            coverage.put("volume", containsAny(normalized, "分卷", "卷纲", "卷目标"));
            coverage.put("chapter", containsAny(normalized, "章节", "章纲", "章末"));
            coverage.put("arc", containsAny(normalized, "成长", "转折", "主线", "阶段"));
            coverage.put("boundary", containsAny(normalized, "边界", "must_write", "must_not_write", "伏笔"));
            coverage.put("pace", containsAny(normalized, "节奏", "爽点", "悬念", "牵引"));
        } else if ("review".equals(type)) {
            coverage.put("dimensions", containsAny(normalized, "审查维度", "检查", "质量", "评分"));
            coverage.put("continuity", containsAny(normalized, "一致性", "连续性", "canon", "记忆"));
            coverage.put("boundary", containsAny(normalized, "章节边界", "越界", "后续章纲"));
            coverage.put("revision", containsAny(normalized, "修改", "返修", "建议", "问题"));
            coverage.put("evidence", containsAny(normalized, "证据", "引用", "定位", "依据"));
        } else {
            coverage.put("purpose", containsAny(normalized, "用途", "目标", "说明", "适用"));
            coverage.put("rules", containsAny(normalized, "规则", "必须", "检查", "约束"));
            coverage.put("source", containsAny(normalized, "来源", "样本", "证据", "分析"));
            coverage.put("scope", containsAny(normalized, "scope", "作用域", "适用范围", "应用场景"));
        }
        return coverage;
    }

    private int ruleSpecificityScore(String normalized) {
        int score = 30;
        score += Math.min(30, keywordCount(normalized, "必须", "应当", "需要", "保持") * 5);
        score += Math.min(20, keywordCount(normalized, "禁止", "不得", "避免", "不要") * 5);
        score += Math.min(20, keywordCount(normalized, "检查清单", "控制要点", "验收", "标准") * 5);
        return score;
    }

    private int evidenceDensityScore(String content, List<Map<String, Object>> sourceTrace, List<Map<String, Object>> evidenceItems) {
        int score = 20;
        score += Math.min(30, sourceTrace.size() * 10);
        score += Math.min(30, evidenceItems.size() * 8);
        score += Math.min(20, keywordCount(content.toLowerCase(Locale.ROOT), "示例", "引用", "证据", "出现率", "占比") * 4);
        return score;
    }

    private int riskControlScore(String normalized) {
        int score = 20;
        if (containsAny(normalized, "复刻", "照搬", "抄袭", "样本文字")) score += 20;
        if (containsAny(normalized, "章节边界", "边界控制", "后续章纲", "不得提前")) score += 25;
        if (containsAny(normalized, "冲突", "矛盾", "优先级", "scope")) score += 15;
        if (containsAny(normalized, "禁止", "不得", "避免", "不要")) score += 20;
        return score;
    }

    private int maintainabilityScore(String content, List<Map<String, Object>> checks) {
        int score = 40;
        score += Math.min(25, headingCount(content) * 5);
        if (!TEMPLATE_PLACEHOLDER_PATTERN.matcher(content).find()) score += 25;
        if (content.length() >= 1200) score += 10;
        if (checks.stream().noneMatch(check -> !Boolean.TRUE.equals(check.get("passed")) && Boolean.TRUE.equals(check.get("required")))) {
            score += 10;
        }
        return score;
    }

    private List<Map<String, Object>> semanticRisks(List<Map<String, Object>> dimensions, String normalized) {
        List<Map<String, Object>> risks = new ArrayList<>();
        for (Map<String, Object> dimension : dimensions) {
            int score = asInteger(dimension.get("score"), 0);
            if (score < 60) {
                Map<String, Object> risk = new LinkedHashMap<>();
                risk.put("type", "low_" + dimension.get("id"));
                risk.put("severity", score < 40 ? "high" : "medium");
                risk.put("message", dimension.get("title") + "不足，当前分数 " + score);
                risks.add(risk);
            }
        }
        if (containsAny(normalized, "{{", "____")) {
            risks.add(Map.of(
                "type", "template_placeholder",
                "severity", "high",
                "message", "Skill 中仍有模板占位符"
            ));
        }
        return risks;
    }

    private List<String> semanticRecommendations(List<Map<String, Object>> dimensions, String type, String normalized) {
        List<String> recommendations = new ArrayList<>();
        for (Map<String, Object> dimension : dimensions) {
            int score = asInteger(dimension.get("score"), 0);
            if (score >= 80) {
                continue;
            }
            String id = asString(dimension.get("id"), "");
            switch (id) {
                case "topic_coverage" -> recommendations.add("补齐 " + type + " Skill 的核心主题覆盖，尤其是边界、节奏、证据或审查维度。");
                case "rule_specificity" -> recommendations.add("把抽象描述改写为必须/禁止/验收清单，减少主观解释空间。");
                case "evidence_density" -> recommendations.add("增加样本来源、分析产物路径、出现率或示例片段，形成可追溯证据链。");
                case "risk_control" -> recommendations.add("补充复刻风险、章节越界、冲突优先级和降级处理规则。");
                case "maintainability" -> recommendations.add("整理标题层级并清理模板占位符，便于版本审阅和恢复。");
                default -> recommendations.add("补强 " + dimension.get("title") + "。");
            }
        }
        if (!containsAny(normalized, "语义", "质量", "验收", "审查")) {
            recommendations.add("增加语义级质量验收标准，说明人工或 Agent 如何判断该 Skill 是否可用。");
        }
        return recommendations.stream().distinct().toList();
    }

    private int keywordCount(String normalized, String... keywords) {
        int count = 0;
        for (String keyword : keywords) {
            String needle = keyword.toLowerCase(Locale.ROOT);
            int index = 0;
            while ((index = normalized.indexOf(needle, index)) >= 0) {
                count++;
                index += Math.max(1, needle.length());
            }
        }
        return count;
    }

    private int headingCount(String content) {
        Matcher matcher = Pattern.compile("(?m)^#{1,6}\\s+.+$").matcher(content);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private int conflictCountForSkill(String projectId, String skillName) {
        List<Map<String, Object>> conflicts = detectSkillConflicts(projectId, listSkills(projectId));
        int count = 0;
        for (Map<String, Object> conflict : conflicts) {
            if (skillName.equals(conflict.get("skillA")) || skillName.equals(conflict.get("skillB"))) {
                count++;
            }
        }
        return count;
    }

    private boolean containsAny(String normalizedText, String... needles) {
        for (String needle : needles) {
            if (normalizedText.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAny(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> conflictItem(String type, String severity, String left, String right, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("severity", severity);
        item.put("skillA", left);
        item.put("skillB", right);
        item.put("message", message);
        item.put("suggestion", conflictSuggestion(type));
        return item;
    }

    private Map<String, Object> conflictSeverityCounts(List<Map<String, Object>> conflicts) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("error", 0);
        counts.put("warning", 0);
        counts.put("info", 0);
        for (Map<String, Object> conflict : conflicts) {
            String severity = asString(conflict.get("severity"), "info");
            counts.put(severity, asInteger(counts.get(severity), 0) + 1);
        }
        return counts;
    }

    private String conflictSuggestion(String type) {
        return switch (type) {
            case "explicit_conflict" -> "停用其中一个 Skill，或移除 conflicts_with 并记录人工审批依据。";
            case "scope_overlap" -> "拆分 scope 或调整类型，避免同一任务同时命中多个同类 Skill。";
            case "priority_tie" -> "调高主 Skill 优先级，或把备用 Skill 降为更窄 scope。";
            case "semantic_rule_conflict" -> "统一必须/禁止规则，并在较低优先级 Skill 中标注服从上游规则。";
            default -> "检查 Skill scope、优先级和审批状态，保留明确的路由依据。";
        };
    }

    private List<String> semanticRuleConflicts(String leftContent, String rightContent) {
        Map<String, String> topics = Map.ofEntries(
            Map.entry("第一人称", "first_person"),
            Map.entry("第三人称", "third_person"),
            Map.entry("快节奏", "fast_pace"),
            Map.entry("慢节奏", "slow_pace"),
            Map.entry("多视角", "multi_pov"),
            Map.entry("单视角", "single_pov"),
            Map.entry("章末钩子", "ending_hook"),
            Map.entry("心理描写", "inner_monologue"),
            Map.entry("战斗场面", "battle_scene"),
            Map.entry("日常铺垫", "slice_of_life"),
            Map.entry("伏笔", "foreshadowing"),
            Map.entry("提前揭示", "early_reveal")
        );
        Map<String, Integer> leftSignals = semanticSignals(leftContent, topics);
        Map<String, Integer> rightSignals = semanticSignals(rightContent, topics);
        List<String> conflicts = new ArrayList<>();
        for (Map.Entry<String, String> topic : topics.entrySet()) {
            int left = leftSignals.getOrDefault(topic.getValue(), 0);
            int right = rightSignals.getOrDefault(topic.getValue(), 0);
            if (left != 0 && right != 0 && left + right == 0) {
                conflicts.add(topic.getKey());
            }
        }
        return conflicts;
    }

    private Map<String, Integer> semanticSignals(String content, Map<String, String> topics) {
        Map<String, Integer> signals = new LinkedHashMap<>();
        if (content == null || content.isBlank()) {
            return signals;
        }
        String normalized = content.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> topic : topics.entrySet()) {
            String text = topic.getKey().toLowerCase(Locale.ROOT);
            int polarity = polarityAroundTopic(normalized, text);
            if (polarity != 0) {
                signals.put(topic.getValue(), polarity);
            }
        }
        return signals;
    }

    private int polarityAroundTopic(String normalized, String topic) {
        int position = normalized.indexOf(topic);
        if (position < 0) {
            return 0;
        }
        int start = Math.max(0, position - 16);
        int end = Math.min(normalized.length(), position + topic.length() + 16);
        String window = normalized.substring(start, end);
        boolean positive = containsAny(window, "必须", "保持", "强化", "优先", "增加", "使用", "应当", "需要");
        boolean negative = containsAny(window, "禁止", "不得", "避免", "不要", "减少", "弱化", "不应");
        if (positive == negative) {
            return 0;
        }
        return positive ? 1 : -1;
    }

    private String skillContent(String projectId, String skillName) {
        try {
            return Files.readString(resolveSkillFile(projectId, skillName), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private List<String> limitedLines(String text, int limit) {
        String[] lines = text.split("\\R", -1);
        List<String> result = new ArrayList<>();
        int safeLimit = Math.max(1, limit);
        for (int i = 0; i < lines.length && i < safeLimit; i += 1) {
            result.add(lines[i]);
        }
        return result;
    }

    private int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.split("\\R", -1).length;
    }

    private List<Map<String, Object>> lineDiff(List<String> left, List<String> right) {
        int[][] lcs = new int[left.size() + 1][right.size() + 1];
        for (int i = left.size() - 1; i >= 0; i -= 1) {
            for (int j = right.size() - 1; j >= 0; j -= 1) {
                if (left.get(i).equals(right.get(j))) {
                    lcs[i][j] = lcs[i + 1][j + 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
        }

        List<Map<String, Object>> diff = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < left.size() && j < right.size()) {
            if (left.get(i).equals(right.get(j))) {
                diff.add(diffLine("unchanged", i + 1, j + 1, left.get(i)));
                i += 1;
                j += 1;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                diff.add(diffLine("removed", i + 1, null, left.get(i)));
                i += 1;
            } else {
                diff.add(diffLine("added", null, j + 1, right.get(j)));
                j += 1;
            }
        }
        while (i < left.size()) {
            diff.add(diffLine("removed", i + 1, null, left.get(i)));
            i += 1;
        }
        while (j < right.size()) {
            diff.add(diffLine("added", null, j + 1, right.get(j)));
            j += 1;
        }
        return diff;
    }

    private Map<String, Object> diffLine(String type, Integer leftLine, Integer rightLine, String text) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("leftLine", leftLine);
        item.put("rightLine", rightLine);
        item.put("text", text);
        return item;
    }

    private long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    private long snapshotSize(Path file) {
        return fileSize(file);
    }

    private String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    private String stripSuffix(String text, String suffix) {
        return text != null && text.endsWith(suffix)
            ? text.substring(0, text.length() - suffix.length())
            : text;
    }

    private String modifiedAt(Path path) {
        try {
            return LocalDateTime.ofInstant(
                Files.getLastModifiedTime(path).toInstant(),
                ZoneId.systemDefault()
            ).toString();
        } catch (IOException e) {
            return "";
        }
    }

    private String inferType(String skillName) {
        return skillName.endsWith("_skill")
            ? skillName.substring(0, skillName.length() - "_skill".length())
            : "general";
    }

    private int defaultPriority(String type) {
        return switch (type) {
            case "writing" -> 100;
            case "outline" -> 90;
            case "review" -> 80;
            default -> 50;
        };
    }

    private List<String> defaultScope(String type) {
        return switch (type) {
            case "writing" -> List.of("chapter_writing", "style_guidance");
            case "outline" -> List.of("outline_generation", "chapter_boundary");
            case "review" -> List.of("chapter_review", "quality_check");
            default -> List.of("general");
        };
    }

    private String asString(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private Boolean asBoolean(Object value, Boolean fallback) {
        return value instanceof Boolean bool ? bool : fallback;
    }

    private Boolean asBooleanFlexible(Object value, Boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return fallback;
    }

    private Integer asInteger(Object value, Integer fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }

        return list.stream()
            .map(String::valueOf)
            .toList();
    }

    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        raw.forEach((key, item) -> map.put(String.valueOf(key), item));
        return map;
    }
}
