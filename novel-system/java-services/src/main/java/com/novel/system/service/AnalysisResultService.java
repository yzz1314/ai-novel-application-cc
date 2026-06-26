package com.novel.system.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.system.entity.AnalysisResult;
import com.novel.system.entity.Sample;
import com.novel.system.exception.ResourceNotFoundException;
import com.novel.system.repository.AnalysisResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisResultService {

    private final SampleService sampleService;
    private final AnalysisResultRepository analysisResultRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${file.storage.base-path:/workspace}")
    private String basePath;

    @Transactional
    public Map<String, Object> syncAnalysisResultsFromWorkspace(String projectId, String sampleId) {
        Sample sample = getProjectSample(projectId, sampleId);
        analysisResultRepository.deleteBySampleId(sampleId);

        Path dir = analysisDir(projectId, sampleId);
        if (!Files.exists(dir)) {
            dir = legacyAnalysisDir(projectId, sampleId);
        }

        if (!Files.exists(dir)) {
            log.warn("Analysis result directory not found, synced zero rows: project={}, sample={}", projectId, sampleId);
            return buildSummary(sample, List.of());
        }

        List<AnalysisResult> entities;
        try (var stream = Files.list(dir)) {
            Path sourceDir = dir;
            entities = stream
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith("_analysis.json"))
                .sorted()
                .map(path -> readAnalysisResult(projectId, sampleId, sourceDir, path))
                .sorted(Comparator.comparing(AnalysisResult::getChunkIndex, Comparator.nullsLast(Integer::compareTo)))
                .toList();
        } catch (IOException e) {
            throw new RuntimeException("同步逐块分析结果到数据库失败: " + sampleId, e);
        }

        List<AnalysisResult> savedEntities = analysisResultRepository.saveAll(entities);
        log.info("Synced analysis results to DB: sample={}, results={}", sampleId, savedEntities.size());
        return buildSummary(sample, savedEntities);
    }

    public Map<String, Object> getAnalysisResults(String projectId, String sampleId) {
        Sample sample = getProjectSample(projectId, sampleId);
        List<AnalysisResult> results = analysisResultRepository.findBySampleIdOrderByChunkIndexAsc(sampleId);
        return buildSummary(sample, results);
    }

    public AnalysisResult getAnalysisResult(String projectId, String sampleId, String chunkId) {
        getProjectSample(projectId, sampleId);
        return analysisResultRepository.findBySampleIdAndChunkId(sampleId, chunkId)
            .orElseThrow(() -> new ResourceNotFoundException("数据库逐块分析结果不存在: " + chunkId));
    }

    public Map<String, Object> getAnalysisResultStats(String projectId, String sampleId) {
        Sample sample = getProjectSample(projectId, sampleId);
        return Map.of(
            "projectId", projectId,
            "sampleId", sampleId,
            "sampleStatus", sample.getStatus().name(),
            "dbAnalysisResultCount", analysisResultRepository.countBySampleId(sampleId),
            "dbSuccessfulAnalysisResultCount", analysisResultRepository.countBySampleIdAndStatus(sampleId, "SUCCESS")
        );
    }

    private AnalysisResult readAnalysisResult(String projectId, String sampleId, Path sourceDir, Path path) {
        try {
            Map<String, Object> raw = objectMapper.readValue(path.toFile(), new TypeReference<>() {});
            String chunkId = stringValue(raw.get("chunk_id"));
            if (chunkId.isBlank()) {
                chunkId = stripSuffix(path.getFileName().toString(), "_analysis.json");
            }

            Map<String, Object> analysis = mapValue(raw.get("analysis"));

            AnalysisResult entity = new AnalysisResult();
            entity.setId(sampleId + "_" + chunkId);
            entity.setProjectId(projectId);
            entity.setSampleId(sampleId);
            entity.setChunkId(chunkId);
            entity.setChunkIndex(toInteger(raw.get("chunk_index")));
            entity.setStartPos(toInteger(raw.get("start_pos")));
            entity.setEndPos(toInteger(raw.get("end_pos")));
            entity.setChapterRange(stringValue(raw.get("chapter_range")));
            entity.setStatus("SUCCESS");
            entity.setSummary(stringValue(analysis.get("summary")));
            entity.setPlotFunction(stringValue(analysis.get("plot_function")));
            entity.setReaderHook(stringValue(analysis.get("reader_hook")));
            entity.setCharacterCount(listSize(analysis.get("characters")));
            entity.setSceneTechniqueCount(listSize(analysis.get("scene_techniques")));
            entity.setProseTechniqueCount(listSize(analysis.get("prose_techniques")));
            entity.setOutlineTechniqueCount(listSize(analysis.get("outline_techniques")));
            entity.setAppealPointCount(listSize(analysis.get("appeal_points")));
            entity.setAnalysis(analysis);
            entity.setRawResult(raw);
            entity.setAnalysisPath(relative(projectId, path));
            entity.setAnalyzedAt(parseDateTime(raw.get("analyzed_at")));
            return entity;
        } catch (IOException e) {
            throw new RuntimeException("读取逐块分析结果失败: " + sourceDir.relativize(path), e);
        }
    }

    private Map<String, Object> buildSummary(Sample sample, List<AnalysisResult> results) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("projectId", sample.getProjectId());
        response.put("sampleId", sample.getId());
        response.put("sampleStatus", sample.getStatus().name());
        response.put("dbAnalysisResultCount", results.size());
        response.put("dbSuccessfulAnalysisResultCount", results.stream()
            .filter(result -> "SUCCESS".equals(result.getStatus()))
            .count());
        response.put("results", results);
        return response;
    }

    private Sample getProjectSample(String projectId, String sampleId) {
        Sample sample = sampleService.getSample(sampleId);
        if (!projectId.equals(sample.getProjectId())) {
            throw new ResourceNotFoundException("样本不属于当前项目: " + sampleId);
        }
        return sample;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return new LinkedHashMap<>();
    }

    private int listSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDateTime parseDateTime(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.toString());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private Path projectRoot(String projectId) {
        return Paths.get(basePath, "projects", projectId).normalize();
    }

    private Path analysisDir(String projectId, String sampleId) {
        return projectRoot(projectId).resolve("analysis").resolve("per_chunk").resolve(sampleId);
    }

    private Path legacyAnalysisDir(String projectId, String sampleId) {
        return projectRoot(projectId).resolve("samples").resolve("analysis").resolve(sampleId);
    }

    private String relative(String projectId, Path path) {
        return projectRoot(projectId).relativize(path).toString().replace("\\", "/");
    }

    private String stripSuffix(String value, String suffix) {
        return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
