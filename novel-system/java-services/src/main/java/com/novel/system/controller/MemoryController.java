package com.novel.system.controller;

import com.novel.system.dto.response.TaskResponse;
import com.novel.system.entity.Task;
import com.novel.system.service.MemoryArtifactService;
import com.novel.system.service.MemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryService memoryService;
    private final MemoryArtifactService memoryArtifactService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getOverview(@PathVariable String projectId) {
        return ResponseEntity.ok(memoryService.getOverview(projectId));
    }

    @GetMapping("/db")
    public ResponseEntity<Map<String, Object>> getMemoryFromDb(@PathVariable String projectId) {
        return ResponseEntity.ok(memoryArtifactService.getMemory(projectId));
    }

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncMemoryToDb(
            @PathVariable String projectId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(memoryArtifactService.syncMemoryFromWorkspace(projectId, request == null ? Map.of() : request));
    }

    @GetMapping("/versions")
    public ResponseEntity<List<Map<String, Object>>> listVersions(@PathVariable String projectId) {
        return ResponseEntity.ok(memoryService.listVersions(projectId));
    }

    @PostMapping("/versions")
    public ResponseEntity<Map<String, Object>> createVersion(
            @PathVariable String projectId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(memoryService.createVersion(projectId, request == null ? Map.of() : request));
    }

    @GetMapping("/versions/{versionId}")
    public ResponseEntity<Map<String, Object>> getVersion(
            @PathVariable String projectId,
            @PathVariable String versionId) {
        return ResponseEntity.ok(memoryService.getVersion(projectId, versionId));
    }

    @PostMapping("/versions/{versionId}/restore")
    public ResponseEntity<Map<String, Object>> restoreVersion(
            @PathVariable String projectId,
            @PathVariable String versionId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(memoryService.restoreVersion(projectId, versionId, request == null ? Map.of() : request));
    }

    @GetMapping("/{type}")
    public ResponseEntity<Map<String, Object>> getMemoryType(
            @PathVariable String projectId,
            @PathVariable String type) {
        return ResponseEntity.ok(memoryService.getMemoryType(projectId, type));
    }

    @GetMapping("/characters/list")
    public ResponseEntity<List<Map<String, Object>>> getCharacters(@PathVariable String projectId) {
        return ResponseEntity.ok(memoryService.getCharacters(projectId));
    }

    @GetMapping("/world-settings/list")
    public ResponseEntity<List<Map<String, Object>>> getWorldSettings(@PathVariable String projectId) {
        return ResponseEntity.ok(memoryService.getWorldSettings(projectId));
    }

    @GetMapping("/timeline/list")
    public ResponseEntity<List<Map<String, Object>>> getTimeline(@PathVariable String projectId) {
        return ResponseEntity.ok(memoryService.getTimeline(projectId));
    }

    @GetMapping("/snapshots/list")
    public ResponseEntity<List<Map<String, Object>>> listSnapshots(@PathVariable String projectId) {
        return ResponseEntity.ok(memoryService.listSnapshots(projectId));
    }

    @GetMapping("/snapshots/{snapshotId}")
    public ResponseEntity<Map<String, Object>> getSnapshot(
            @PathVariable String projectId,
            @PathVariable String snapshotId) {
        return ResponseEntity.ok(memoryService.getSnapshot(projectId, snapshotId));
    }

    @GetMapping("/continuity/reports")
    public ResponseEntity<List<Map<String, Object>>> listContinuityReports(@PathVariable String projectId) {
        return ResponseEntity.ok(memoryService.listContinuityReports(projectId));
    }

    @GetMapping("/continuity/reports/{reportId}")
    public ResponseEntity<Map<String, Object>> getContinuityReport(
            @PathVariable String projectId,
            @PathVariable String reportId) {
        return ResponseEntity.ok(memoryService.getContinuityReport(projectId, reportId));
    }

    @PostMapping("/continuity/reports/{reportId}/issues/{issueIndex}/resolution")
    public ResponseEntity<Map<String, Object>> resolveContinuityIssue(
            @PathVariable String projectId,
            @PathVariable String reportId,
            @PathVariable Integer issueIndex,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(memoryService.resolveContinuityIssue(
            projectId,
            reportId,
            issueIndex,
            request == null ? Map.of() : request
        ));
    }

    @PostMapping("/continuity/reports/{reportId}/issues/{issueIndex}/fix")
    public ResponseEntity<Map<String, Object>> applyContinuityIssueFix(
            @PathVariable String projectId,
            @PathVariable String reportId,
            @PathVariable Integer issueIndex,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(memoryService.applyContinuityIssueFix(
            projectId,
            reportId,
            issueIndex,
            request == null ? Map.of() : request
        ));
    }

    @GetMapping("/audits")
    public ResponseEntity<List<Map<String, Object>>> listAuditReports(@PathVariable String projectId) {
        return ResponseEntity.ok(memoryService.listAuditReports(projectId));
    }

    @GetMapping("/audits/{reportId}")
    public ResponseEntity<Map<String, Object>> getAuditReport(
            @PathVariable String projectId,
            @PathVariable String reportId) {
        return ResponseEntity.ok(memoryService.getAuditReport(projectId, reportId));
    }

    @PostMapping("/audits/{reportId}/issues/{issueIndex}/resolution")
    public ResponseEntity<Map<String, Object>> resolveAuditIssue(
            @PathVariable String projectId,
            @PathVariable String reportId,
            @PathVariable Integer issueIndex,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(memoryService.resolveAuditIssue(
            projectId,
            reportId,
            issueIndex,
            request == null ? Map.of() : request
        ));
    }

    @PostMapping("/audits/{reportId}/issues/{issueIndex}/fix")
    public ResponseEntity<Map<String, Object>> applyAuditIssueFix(
            @PathVariable String projectId,
            @PathVariable String reportId,
            @PathVariable Integer issueIndex,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(memoryService.applyAuditIssueFix(
            projectId,
            reportId,
            issueIndex,
            request == null ? Map.of() : request
        ));
    }

    @PostMapping("/audit")
    public ResponseEntity<TaskResponse> auditMemory(
            @PathVariable String projectId,
            @RequestBody(required = false) Map<String, Object> request) {
        Task task = memoryService.auditMemory(projectId, request);
        return ResponseEntity.ok(TaskResponse.from(task));
    }

    @PostMapping("/continuity/check")
    public ResponseEntity<TaskResponse> checkContinuity(
            @PathVariable String projectId,
            @RequestBody(required = false) Map<String, Object> request) {
        Task task = memoryService.checkContinuity(projectId, request);
        return ResponseEntity.ok(TaskResponse.from(task));
    }

    @PostMapping("/rebuild")
    public ResponseEntity<TaskResponse> rebuildMemory(
            @PathVariable String projectId,
            @RequestBody(required = false) Map<String, Object> request) {
        Task task = memoryService.rebuildMemory(projectId, request);
        return ResponseEntity.ok(TaskResponse.from(task));
    }
}
