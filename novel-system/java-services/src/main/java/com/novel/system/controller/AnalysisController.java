package com.novel.system.controller;

import com.novel.system.dto.response.TaskResponse;
import com.novel.system.entity.AnalysisResult;
import com.novel.system.entity.Task;
import com.novel.system.service.AnalysisArtifactService;
import com.novel.system.service.AnalysisResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/analysis")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisArtifactService analysisArtifactService;
    private final AnalysisResultService analysisResultService;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String projectId) {
        return ResponseEntity.ok(analysisArtifactService.getProjectAnalysisStatus(projectId));
    }

    @GetMapping("/report")
    public ResponseEntity<Map<String, Object>> getReport(@PathVariable String projectId) {
        return ResponseEntity.ok(analysisArtifactService.getProjectAnalysisReport(projectId));
    }

    @GetMapping("/cross-book")
    public ResponseEntity<Map<String, Object>> getCrossBookReport(@PathVariable String projectId) {
        return ResponseEntity.ok(analysisArtifactService.getCrossBookReport(projectId));
    }

    @GetMapping("/samples/{sampleId}/artifacts")
    public ResponseEntity<Map<String, Object>> getSampleArtifacts(
            @PathVariable String projectId,
            @PathVariable String sampleId) {
        return ResponseEntity.ok(analysisArtifactService.getSampleArtifacts(projectId, sampleId));
    }

    @GetMapping("/samples/{sampleId}/manifest")
    public ResponseEntity<Map<String, Object>> getManifest(
            @PathVariable String projectId,
            @PathVariable String sampleId) {
        return ResponseEntity.ok(analysisArtifactService.getSampleManifest(projectId, sampleId));
    }

    @GetMapping("/samples/{sampleId}/chunks")
    public ResponseEntity<List<Map<String, Object>>> listChunks(
            @PathVariable String projectId,
            @PathVariable String sampleId) {
        return ResponseEntity.ok(analysisArtifactService.listSampleChunks(projectId, sampleId));
    }

    @GetMapping("/samples/{sampleId}/chunks/{chunkId}")
    public ResponseEntity<Map<String, Object>> getChunk(
            @PathVariable String projectId,
            @PathVariable String sampleId,
            @PathVariable String chunkId) {
        return ResponseEntity.ok(analysisArtifactService.getSampleChunk(projectId, sampleId, chunkId));
    }

    @GetMapping("/samples/{sampleId}/chunks/{chunkId}/analysis")
    public ResponseEntity<Map<String, Object>> getChunkAnalysis(
            @PathVariable String projectId,
            @PathVariable String sampleId,
            @PathVariable String chunkId) {
        return ResponseEntity.ok(analysisArtifactService.getChunkAnalysis(projectId, sampleId, chunkId));
    }

    @GetMapping("/samples/{sampleId}/chunks/{chunkId}/analysis/db")
    public ResponseEntity<AnalysisResult> getChunkAnalysisFromDb(
            @PathVariable String projectId,
            @PathVariable String sampleId,
            @PathVariable String chunkId) {
        return ResponseEntity.ok(analysisResultService.getAnalysisResult(projectId, sampleId, chunkId));
    }

    @GetMapping("/samples/{sampleId}/analysis")
    public ResponseEntity<Map<String, Object>> getSampleAnalysis(
            @PathVariable String projectId,
            @PathVariable String sampleId) {
        return ResponseEntity.ok(analysisArtifactService.getSampleAnalysis(projectId, sampleId));
    }

    @GetMapping("/samples/{sampleId}/analysis/db")
    public ResponseEntity<Map<String, Object>> getSampleAnalysisFromDb(
            @PathVariable String projectId,
            @PathVariable String sampleId) {
        return ResponseEntity.ok(analysisResultService.getAnalysisResults(projectId, sampleId));
    }

    @PostMapping("/samples/{sampleId}/analysis/sync")
    public ResponseEntity<Map<String, Object>> syncSampleAnalysisToDb(
            @PathVariable String projectId,
            @PathVariable String sampleId) {
        return ResponseEntity.ok(analysisResultService.syncAnalysisResultsFromWorkspace(projectId, sampleId));
    }

    @GetMapping("/samples/{sampleId}/coverage")
    public ResponseEntity<Map<String, Object>> getSampleCoverage(
            @PathVariable String projectId,
            @PathVariable String sampleId) {
        return ResponseEntity.ok(analysisArtifactService.getSampleCoverage(projectId, sampleId));
    }

    @PostMapping("/samples/{sampleId}/coverage/check")
    public ResponseEntity<TaskResponse> checkSampleCoverage(
            @PathVariable String projectId,
            @PathVariable String sampleId) {
        Task task = analysisArtifactService.checkSampleCoverage(projectId, sampleId);
        return ResponseEntity.ok(TaskResponse.from(task));
    }

    @GetMapping("/samples/{sampleId}/book-report")
    public ResponseEntity<Map<String, Object>> getBookReport(
            @PathVariable String projectId,
            @PathVariable String sampleId) {
        return ResponseEntity.ok(analysisArtifactService.getBookReport(projectId, sampleId));
    }
}
