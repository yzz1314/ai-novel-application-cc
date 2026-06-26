package com.novel.system.controller;

import com.novel.system.service.ArtifactService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/artifacts")
@RequiredArgsConstructor
public class ArtifactController {

    private final ArtifactService artifactService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getOverview(@PathVariable String projectId) {
        return ResponseEntity.ok(artifactService.getOverview(projectId));
    }

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> listArtifacts(
            @PathVariable String projectId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "200") int limit) {
        return ResponseEntity.ok(artifactService.listArtifacts(projectId, category, query, limit));
    }

    @GetMapping("/view")
    public ResponseEntity<Map<String, Object>> getArtifact(
            @PathVariable String projectId,
            @RequestParam("path") String path) {
        return ResponseEntity.ok(artifactService.getArtifact(projectId, path));
    }

    @GetMapping("/download")
    public ResponseEntity<?> downloadArtifact(
            @PathVariable String projectId,
            @RequestParam("path") String path) {
        ArtifactService.DownloadedArtifact artifact = artifactService.downloadArtifact(projectId, path);
        return ResponseEntity.ok()
            .contentType(artifact.mediaType())
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(artifact.filename()).build().toString()
            )
            .body(artifact.resource());
    }
}
