package com.novel.system.controller;

import com.novel.system.dto.response.TaskResponse;
import com.novel.system.entity.Task;
import com.novel.system.service.GraphArtifactDbService;
import com.novel.system.service.GraphArtifactService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class GraphController {

    private final GraphArtifactService graphArtifactService;
    private final GraphArtifactDbService graphArtifactDbService;

    @GetMapping("/api/projects/{projectId}/graph")
    public ResponseEntity<Map<String, Object>> getProjectGraph(@PathVariable String projectId) {
        return ResponseEntity.ok(graphArtifactService.getGraph(projectId, "default"));
    }

    @GetMapping("/api/projects/{projectId}/graph/db")
    public ResponseEntity<Map<String, Object>> getProjectGraphFromDb(@PathVariable String projectId) {
        return ResponseEntity.ok(graphArtifactDbService.getGraph(projectId, "default"));
    }

    @PostMapping("/api/projects/{projectId}/graph/sync")
    public ResponseEntity<Map<String, Object>> syncProjectGraphToDb(
            @PathVariable String projectId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(graphArtifactDbService.syncGraphFromWorkspace(projectId, request == null ? Map.of() : request));
    }

    @GetMapping("/api/projects/{projectId}/graphs/db")
    public ResponseEntity<java.util.List<Map<String, Object>>> listProjectGraphsFromDb(@PathVariable String projectId) {
        return ResponseEntity.ok(graphArtifactDbService.listGraphs(projectId));
    }

    @PostMapping("/api/projects/{projectId}/graph/query")
    public ResponseEntity<Map<String, Object>> queryProjectGraph(
            @PathVariable String projectId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(graphArtifactService.queryGraph(projectId, "default", request == null ? Map.of() : request));
    }

    @GetMapping("/api/projects/{projectId}/graph/query-cache")
    public ResponseEntity<Map<String, Object>> listProjectGraphQueryCaches(@PathVariable String projectId) {
        return ResponseEntity.ok(graphArtifactService.listQueryCaches(projectId, "default"));
    }

    @PostMapping("/api/projects/{projectId}/graph/query-cache/clear")
    public ResponseEntity<Map<String, Object>> clearProjectGraphQueryCaches(@PathVariable String projectId) {
        return ResponseEntity.ok(graphArtifactService.clearQueryCaches(projectId, "default"));
    }

    @PostMapping("/api/projects/{projectId}/graph/rebuild")
    public ResponseEntity<TaskResponse> rebuildProjectGraph(
            @PathVariable String projectId,
            @RequestBody(required = false) Map<String, Object> request) {
        Task task = graphArtifactService.rebuildGraph(projectId, "default", request == null ? Map.of() : request);
        return ResponseEntity.ok(TaskResponse.from(task));
    }

    @GetMapping("/api/projects/{projectId}/graph/export")
    public ResponseEntity<byte[]> exportProjectGraph(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "json") String format) {
        return exported(graphArtifactService.exportGraph(projectId, "default", format));
    }

    @GetMapping("/api/projects/{projectId}/books/{bookId}/graph")
    public ResponseEntity<Map<String, Object>> getBookGraph(
            @PathVariable String projectId,
            @PathVariable String bookId) {
        return ResponseEntity.ok(graphArtifactService.getGraph(projectId, bookId));
    }

    @PostMapping("/api/projects/{projectId}/books/{bookId}/graph/query")
    public ResponseEntity<Map<String, Object>> queryBookGraph(
            @PathVariable String projectId,
            @PathVariable String bookId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(graphArtifactService.queryGraph(projectId, bookId, request == null ? Map.of() : request));
    }

    @GetMapping("/api/projects/{projectId}/books/{bookId}/graph/query-cache")
    public ResponseEntity<Map<String, Object>> listBookGraphQueryCaches(
            @PathVariable String projectId,
            @PathVariable String bookId) {
        return ResponseEntity.ok(graphArtifactService.listQueryCaches(projectId, bookId));
    }

    @PostMapping("/api/projects/{projectId}/books/{bookId}/graph/query-cache/clear")
    public ResponseEntity<Map<String, Object>> clearBookGraphQueryCaches(
            @PathVariable String projectId,
            @PathVariable String bookId) {
        return ResponseEntity.ok(graphArtifactService.clearQueryCaches(projectId, bookId));
    }

    @PostMapping("/api/projects/{projectId}/books/{bookId}/graph/rebuild")
    public ResponseEntity<TaskResponse> rebuildBookGraph(
            @PathVariable String projectId,
            @PathVariable String bookId,
            @RequestBody(required = false) Map<String, Object> request) {
        Task task = graphArtifactService.rebuildGraph(projectId, bookId, request == null ? Map.of() : request);
        return ResponseEntity.ok(TaskResponse.from(task));
    }

    @GetMapping("/api/projects/{projectId}/books/{bookId}/graph/export")
    public ResponseEntity<byte[]> exportBookGraph(
            @PathVariable String projectId,
            @PathVariable String bookId,
            @RequestParam(defaultValue = "json") String format) {
        return exported(graphArtifactService.exportGraph(projectId, bookId, format));
    }

    @GetMapping("/api/projects/{projectId}/books/{bookId}/graph/db")
    public ResponseEntity<Map<String, Object>> getBookGraphFromDb(
            @PathVariable String projectId,
            @PathVariable String bookId) {
        return ResponseEntity.ok(graphArtifactDbService.getGraph(projectId, bookId));
    }

    @PostMapping("/api/projects/{projectId}/books/{bookId}/graph/sync")
    public ResponseEntity<Map<String, Object>> syncBookGraphToDb(
            @PathVariable String projectId,
            @PathVariable String bookId,
            @RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(request == null ? Map.of() : request);
        payload.put("book_id", bookId);
        return ResponseEntity.ok(graphArtifactDbService.syncGraphFromWorkspace(projectId, payload));
    }

    private ResponseEntity<byte[]> exported(GraphArtifactService.ExportedGraph exportedGraph) {
        return ResponseEntity.ok()
            .contentType(exportedGraph.mediaType())
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(exportedGraph.filename()).build().toString()
            )
            .body(exportedGraph.bytes());
    }
}
