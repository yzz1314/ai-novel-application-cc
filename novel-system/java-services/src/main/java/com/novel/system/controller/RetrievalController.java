package com.novel.system.controller;

import com.novel.system.dto.response.TaskResponse;
import com.novel.system.entity.Task;
import com.novel.system.service.RetrievalArtifactDbService;
import com.novel.system.service.RetrievalArtifactService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/retrieval")
@RequiredArgsConstructor
public class RetrievalController {

    private final RetrievalArtifactService retrievalArtifactService;
    private final RetrievalArtifactDbService retrievalArtifactDbService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getOverview(@PathVariable String projectId) {
        return ResponseEntity.ok(retrievalArtifactService.getOverview(projectId));
    }

    @GetMapping("/db")
    public ResponseEntity<Map<String, Object>> getRetrievalFromDb(@PathVariable String projectId) {
        return ResponseEntity.ok(retrievalArtifactDbService.getRetrieval(projectId));
    }

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncRetrievalToDb(
            @PathVariable String projectId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(retrievalArtifactDbService.syncRetrievalFromWorkspace(projectId, request == null ? Map.of() : request));
    }

    @GetMapping("/list/db")
    public ResponseEntity<List<Map<String, Object>>> listRetrievalFromDb(@PathVariable String projectId) {
        return ResponseEntity.ok(retrievalArtifactDbService.listRetrieval(projectId));
    }

    @GetMapping("/indexes")
    public ResponseEntity<Map<String, Object>> getIndexSummaries(@PathVariable String projectId) {
        return ResponseEntity.ok(retrievalArtifactService.getIndexSummaries(projectId));
    }

    @GetMapping("/indexes/{indexType}")
    public ResponseEntity<Map<String, Object>> getIndexSummary(
            @PathVariable String projectId,
            @PathVariable String indexType) {
        return ResponseEntity.ok(retrievalArtifactService.getIndexSummary(projectId, indexType));
    }

    @GetMapping("/quality")
    public ResponseEntity<Map<String, Object>> getQualityReport(@PathVariable String projectId) {
        return ResponseEntity.ok(retrievalArtifactService.getQualityReport(projectId));
    }

    @PostMapping("/quality/evaluate")
    public ResponseEntity<Map<String, Object>> evaluateQuality(
            @PathVariable String projectId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(retrievalArtifactService.evaluateQuality(projectId, request == null ? Map.of() : request));
    }

    @GetMapping("/benchmark")
    public ResponseEntity<Map<String, Object>> getBenchmarkReport(@PathVariable String projectId) {
        return ResponseEntity.ok(retrievalArtifactService.getBenchmarkReport(projectId));
    }

    @PostMapping("/benchmark/evaluate")
    public ResponseEntity<TaskResponse> evaluateBenchmark(
            @PathVariable String projectId,
            @RequestBody(required = false) Map<String, Object> request) {
        Task task = retrievalArtifactService.evaluateBenchmark(projectId, request == null ? Map.of() : request);
        return ResponseEntity.ok(TaskResponse.from(task));
    }

    @PostMapping("/invalidate")
    public ResponseEntity<Map<String, Object>> invalidateCaches(
            @PathVariable String projectId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(retrievalArtifactService.invalidateCaches(projectId, request == null ? Map.of() : request));
    }

    @GetMapping("/versions")
    public ResponseEntity<List<Map<String, Object>>> listIndexVersions(@PathVariable String projectId) {
        return ResponseEntity.ok(retrievalArtifactService.listIndexVersions(projectId));
    }

    @PostMapping("/versions")
    public ResponseEntity<Map<String, Object>> createIndexVersion(
            @PathVariable String projectId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(retrievalArtifactService.createIndexVersion(projectId, request == null ? Map.of() : request));
    }

    @GetMapping("/versions/{versionId}")
    public ResponseEntity<Map<String, Object>> getIndexVersion(
            @PathVariable String projectId,
            @PathVariable String versionId) {
        return ResponseEntity.ok(retrievalArtifactService.getIndexVersion(projectId, versionId));
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig(@PathVariable String projectId) {
        return ResponseEntity.ok(retrievalArtifactService.getConfig(projectId));
    }

    @PatchMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(
            @PathVariable String projectId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(retrievalArtifactService.updateConfig(projectId, request == null ? Map.of() : request));
    }

    @PostMapping("/rebuild")
    public ResponseEntity<TaskResponse> rebuildIndexes(
            @PathVariable String projectId,
            @RequestBody(required = false) Map<String, Object> request) {
        Task task = retrievalArtifactService.rebuildIndexes(projectId, request == null ? Map.of() : request);
        return ResponseEntity.ok(TaskResponse.from(task));
    }

    @GetMapping("/context-packs")
    public ResponseEntity<List<Map<String, Object>>> listContextPacks(@PathVariable String projectId) {
        return ResponseEntity.ok(retrievalArtifactService.listContextPacks(projectId));
    }

    @GetMapping("/context-packs/{contextPackId}")
    public ResponseEntity<Map<String, Object>> getContextPack(
            @PathVariable String projectId,
            @PathVariable String contextPackId) {
        return ResponseEntity.ok(retrievalArtifactService.getContextPack(projectId, contextPackId));
    }
}
