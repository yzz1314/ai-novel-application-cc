package com.novel.system.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.system.entity.MemoryArtifact;
import com.novel.system.exception.ResourceNotFoundException;
import com.novel.system.repository.MemoryArtifactRepository;
import com.novel.system.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryArtifactService {

    private final ProjectService projectService;
    private final MemoryArtifactRepository memoryArtifactRepository;
    private final TaskRepository taskRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${file.storage.base-path:/workspace}")
    private String basePath;

    @Transactional
    public Map<String, Object> syncMemoryFromWorkspace(String projectId, Map<String, Object> request) {
        projectService.getProject(projectId);
        String bookId = resolveBookId(request);
        Path memoryDir = memoryDir(projectId);
        Path bookMemoryDir = bookMemoryDir(projectId, bookId);
        List<Map<String, Object>> snapshots = listSnapshots(projectId);
        Map<String, Object> latestSnapshot = snapshots.isEmpty() ? new LinkedHashMap<>() : readJsonMap(resolveSnapshotPath(projectId, String.valueOf(snapshots.get(0).get("id"))));
        Map<String, Object> markdownMemories = markdownMemories(projectId, memoryDir);
        Map<String, Object> memoryJson = memoryJson(projectId, memoryDir);

        MemoryArtifact entity = memoryArtifactRepository.findByProjectId(projectId).orElseGet(MemoryArtifact::new);
        entity.setId(projectId);
        entity.setProjectId(projectId);
        entity.setBookId(bookId);
        entity.setStatus(resolveStatus(memoryDir, bookMemoryDir, snapshots));
        entity.setCharacterCount(listSize(memoryJson.get("characters")));
        entity.setWorldSettingCount(listSize(memoryJson.get("worldSettings")));
        entity.setPlotCount(listSize(memoryJson.get("plots")));
        entity.setSuspenseCount(listSize(memoryJson.get("suspenses")));
        entity.setTimelineEventCount(listSize(memoryJson.get("timelineEvents")));
        entity.setMarkdownCount(markdownMemories.size());
        entity.setSnapshotCount(snapshots.size());
        entity.setMemoryPath(relative(projectId, memoryDir));
        entity.setBookMemoryPath(relative(projectId, bookMemoryDir));
        entity.setLatestSnapshotId(snapshots.isEmpty() ? null : String.valueOf(snapshots.get(0).get("id")));
        entity.setLatestSnapshotPath(snapshots.isEmpty() ? null : String.valueOf(snapshots.get(0).get("path")));
        entity.setMarkdownMemories(markdownMemories);
        entity.setMemoryJson(memoryJson);
        entity.setLatestSnapshot(latestSnapshot);
        entity.setSnapshots(snapshots);
        entity.setLatestTasks(latestMemoryTasks(projectId));
        entity.setMemoryMetadata(metadata(projectId, bookId, memoryDir, bookMemoryDir, snapshots));
        entity.setSyncedAt(LocalDateTime.now());

        MemoryArtifact saved = memoryArtifactRepository.save(entity);
        log.info("Synced memory artifact to DB: project={}, snapshots={}, characters={}, worldSettings={}, plots={}, suspenses={}, timeline={}",
            projectId,
            saved.getSnapshotCount(),
            saved.getCharacterCount(),
            saved.getWorldSettingCount(),
            saved.getPlotCount(),
            saved.getSuspenseCount(),
            saved.getTimelineEventCount());
        return response(saved);
    }

    public Map<String, Object> getMemory(String projectId) {
        projectService.getProject(projectId);
        MemoryArtifact artifact = memoryArtifactRepository.findByProjectId(projectId)
            .orElseThrow(() -> new ResourceNotFoundException("数据库记忆不存在，请先同步: " + projectId));
        return response(artifact);
    }

    public List<Map<String, Object>> listMemories(String projectId) {
        projectService.getProject(projectId);
        return memoryArtifactRepository.findByProjectIdOrderByUpdatedAtDesc(projectId)
            .stream()
            .map(this::summary)
            .toList();
    }

    private Map<String, Object> response(MemoryArtifact entity) {
        Map<String, Object> result = summary(entity);
        result.put("markdownMemories", entity.getMarkdownMemories());
        result.put("memoryJson", entity.getMemoryJson());
        result.put("latestSnapshot", entity.getLatestSnapshot());
        result.put("snapshots", entity.getSnapshots());
        result.put("latestTasks", entity.getLatestTasks());
        result.put("memoryMetadata", entity.getMemoryMetadata());
        return result;
    }

    private Map<String, Object> summary(MemoryArtifact entity) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", entity.getId());
        result.put("projectId", entity.getProjectId());
        result.put("bookId", entity.getBookId());
        result.put("status", entity.getStatus());
        result.put("characterCount", entity.getCharacterCount());
        result.put("worldSettingCount", entity.getWorldSettingCount());
        result.put("plotCount", entity.getPlotCount());
        result.put("suspenseCount", entity.getSuspenseCount());
        result.put("timelineEventCount", entity.getTimelineEventCount());
        result.put("markdownCount", entity.getMarkdownCount());
        result.put("snapshotCount", entity.getSnapshotCount());
        result.put("memoryPath", entity.getMemoryPath());
        result.put("bookMemoryPath", entity.getBookMemoryPath());
        result.put("latestSnapshotId", entity.getLatestSnapshotId());
        result.put("latestSnapshotPath", entity.getLatestSnapshotPath());
        result.put("syncedAt", entity.getSyncedAt());
        result.put("createdAt", entity.getCreatedAt());
        result.put("updatedAt", entity.getUpdatedAt());
        return result;
    }

    private Map<String, Object> markdownMemories(String projectId, Path memoryDir) {
        Map<String, Object> markdownMemories = new LinkedHashMap<>();
        markdownMemories.put("characters", readMarkdown(projectId, memoryDir.resolve("characters.md")));
        markdownMemories.put("foreshadowing", readMarkdown(projectId, memoryDir.resolve("foreshadowing.md")));
        markdownMemories.put("timeline", readMarkdown(projectId, memoryDir.resolve("timeline.md")));
        markdownMemories.put("relationships", readMarkdown(projectId, memoryDir.resolve("relationships.md")));
        markdownMemories.put("cognition", readMarkdown(projectId, memoryDir.resolve("cognition.md")));
        markdownMemories.put("canon", readMarkdown(projectId, memoryDir.resolve("canon.md")));
        return markdownMemories;
    }

    private Map<String, Object> memoryJson(String projectId, Path memoryDir) {
        List<Map<String, Object>> characters = readJsonList(memoryDir.resolve("characters.json"));
        List<Map<String, Object>> worldSettings = readJsonList(memoryDir.resolve("world_settings.json"));
        List<Map<String, Object>> plots = readJsonList(memoryDir.resolve("plots.json"));
        List<Map<String, Object>> suspenses = readJsonList(memoryDir.resolve("suspenses.json"));
        List<Map<String, Object>> timelineEvents = readJsonList(memoryDir.resolve("timeline.json"));

        Map<String, Object> memoryJson = new LinkedHashMap<>();
        memoryJson.put("characters", characters);
        memoryJson.put("worldSettings", worldSettings);
        memoryJson.put("plots", plots);
        memoryJson.put("suspenses", suspenses);
        memoryJson.put("timelineEvents", timelineEvents);
        memoryJson.put("foreshadowing", suspenses);
        memoryJson.put("relationships", deriveRelationships(characters));
        memoryJson.put("cognition", deriveCognition(characters));
        memoryJson.put("canon", Map.of(
            "characters", characters.size(),
            "worldSettings", worldSettings.size(),
            "plots", plots.size(),
            "suspenses", suspenses.size(),
            "timelineEvents", timelineEvents.size(),
            "memoryDir", relative(projectId, memoryDir)
        ));
        return memoryJson;
    }

    private List<Map<String, Object>> deriveRelationships(List<Map<String, Object>> characters) {
        List<Map<String, Object>> relationships = new ArrayList<>();
        for (Map<String, Object> character : characters) {
            String characterName = stringValue(character.get("name"), "");
            Object rawRelationships = character.get("relationships");
            if (!(rawRelationships instanceof Map<?, ?> relMap) || characterName.isBlank()) {
                continue;
            }
            relMap.forEach((target, relation) -> {
                if (target == null || relation == null) {
                    return;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("source", characterName);
                item.put("target", String.valueOf(target));
                item.put("relation", String.valueOf(relation));
                relationships.add(item);
            });
        }
        return relationships;
    }

    private List<Map<String, Object>> deriveCognition(List<Map<String, Object>> characters) {
        List<Map<String, Object>> cognition = new ArrayList<>();
        for (Map<String, Object> character : characters) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("character", character.get("name"));
            item.put("currentStatus", character.get("current_status"));
            item.put("importantEvents", character.get("important_events"));
            item.put("lastUpdated", character.get("last_updated"));
            cognition.add(item);
        }
        return cognition;
    }

    private Map<String, Object> metadata(
            String projectId,
            String bookId,
            Path memoryDir,
            Path bookMemoryDir,
            List<Map<String, Object>> snapshots) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("projectId", projectId);
        metadata.put("bookId", bookId);
        metadata.put("memoryDirModifiedAt", modifiedAt(memoryDir));
        metadata.put("bookMemoryDirModifiedAt", modifiedAt(bookMemoryDir));
        metadata.put("snapshotCount", snapshots.size());
        metadata.put("latestSnapshotUpdatedAt", snapshots.isEmpty() ? null : snapshots.get(0).get("updatedAt"));
        metadata.put("latestTasksCount", latestMemoryTasks(projectId).size());
        metadata.put("markdownTypes", List.of("characters", "foreshadowing", "timeline", "relationships", "cognition", "canon"));
        return metadata;
    }

    private List<Map<String, Object>> latestMemoryTasks(String projectId) {
        return taskRepository.findByProjectId(projectId).stream()
            .filter(task -> "memory_extraction".equals(task.getTaskType()) || "memory_query".equals(task.getTaskType()))
            .sorted(Comparator.comparing(task -> task.getCreatedAt(), Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(10)
            .map(task -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", task.getId());
                item.put("taskType", task.getTaskType());
                item.put("agentName", task.getAgentName());
                item.put("status", task.getStatus().name());
                item.put("inputRefs", task.getInputRefs());
                item.put("outputRefs", task.getOutputRefs());
                item.put("errors", task.getErrors());
                item.put("warnings", task.getWarnings());
                item.put("createdAt", task.getCreatedAt() == null ? null : task.getCreatedAt().toString());
                item.put("finishedAt", task.getFinishedAt() == null ? null : task.getFinishedAt().toString());
                return item;
            })
            .toList();
    }

    private List<Map<String, Object>> listSnapshots(String projectId) {
        Path snapshotsDir = memoryDir(projectId).resolve("snapshots");
        if (!Files.exists(snapshotsDir)) {
            return List.of();
        }

        List<Map<String, Object>> snapshots = new ArrayList<>();
        try (var stream = Files.list(snapshotsDir)) {
            stream
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                .forEach(path -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", stripSuffix(path.getFileName().toString(), ".json"));
                    item.put("path", relative(projectId, path));
                    item.put("updatedAt", modifiedAt(path));
                    readOptionalSnapshot(path, item);
                    snapshots.add(item);
                });
        } catch (IOException e) {
            throw new RuntimeException("读取记忆快照失败", e);
        }

        snapshots.sort(Comparator.comparing(snapshot -> stringValue(snapshot.get("updatedAt"), ""), Comparator.reverseOrder()));
        return snapshots;
    }

    private void readOptionalSnapshot(Path path, Map<String, Object> item) {
        try {
            Map<String, Object> snapshot = readJsonMap(path);
            item.put("chapterId", snapshot.get("chapter_id"));
            item.put("bookId", snapshot.get("book_id"));
            item.put("chapterTitle", snapshot.get("chapter_title"));
            item.put("chapterNumber", snapshot.get("chapter_number"));
            item.put("volumeNumber", snapshot.get("volume_number"));
            item.put("extractedAt", snapshot.get("extracted_at"));
        } catch (Exception ignored) {
        }
    }

    private Map<String, Object> readMarkdown(String projectId, Path file) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", Files.exists(file) ? relative(projectId, file) : null);
        result.put("exists", Files.exists(file));
        result.put("updatedAt", Files.exists(file) ? modifiedAt(file) : null);
        result.put("contentLength", Files.exists(file) ? readText(file).length() : 0);
        result.put("content", Files.exists(file) ? readText(file) : "");
        return result;
    }

    private List<Map<String, Object>> readJsonList(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(file.toFile(), new TypeReference<>() {});
        } catch (IOException e) {
            throw new RuntimeException("读取记忆JSON失败: " + file.getFileName(), e);
        }
    }

    private Map<String, Object> readJsonMap(Path file) {
        try {
            return objectMapper.readValue(file.toFile(), new TypeReference<>() {});
        } catch (IOException e) {
            throw new RuntimeException("读取记忆快照失败: " + file.getFileName(), e);
        }
    }

    private String resolveStatus(Path memoryDir, Path bookMemoryDir, List<Map<String, Object>> snapshots) {
        boolean hasFiles = Files.exists(memoryDir) || Files.exists(bookMemoryDir);
        if (!hasFiles && snapshots.isEmpty()) {
            return "empty";
        }
        return snapshots.isEmpty() ? "synced" : "synced_with_snapshots";
    }

    private String resolveBookId(Map<String, Object> request) {
        if (request == null || request.isEmpty()) {
            return "default";
        }
        Object bookId = request.containsKey("book_id") ? request.get("book_id") : request.get("bookId");
        if (bookId == null || String.valueOf(bookId).isBlank()) {
            return "default";
        }
        return String.valueOf(bookId);
    }

    private Path resolveSnapshotPath(String projectId, String snapshotId) {
        return memoryDir(projectId).resolve("snapshots").resolve(snapshotId + ".json");
    }

    private Path memoryDir(String projectId) {
        return projectRoot(projectId).resolve("memory");
    }

    private Path bookMemoryDir(String projectId, String bookId) {
        return projectRoot(projectId).resolve("books").resolve(bookId).resolve("memories");
    }

    private Path projectRoot(String projectId) {
        return Paths.get(basePath, "projects", projectId).normalize();
    }

    private String relative(String projectId, Path path) {
        if (path == null) {
            return null;
        }
        return projectRoot(projectId).relativize(path).toString().replace("\\", "/");
    }

    private String modifiedAt(Path file) {
        try {
            return LocalDateTime.ofInstant(
                Files.getLastModifiedTime(file).toInstant(),
                ZoneId.systemDefault()
            ).toString();
        } catch (IOException e) {
            return "";
        }
    }

    private String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("读取记忆文件失败: " + file.getFileName(), e);
        }
    }

    private int listSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private String stripSuffix(String value, String suffix) {
        return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
    }

    private String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
