package com.novel.system.controller;

import com.novel.system.dto.request.CreateProjectRequest;
import com.novel.system.dto.response.ProjectResponse;
import com.novel.system.entity.Project;
import com.novel.system.entity.Project.SampleGroupType;
import com.novel.system.security.RequestAccessContextHolder;
import com.novel.system.service.ProjectAccessService;
import com.novel.system.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectAccessService projectAccessService;

    /**
     * 创建项目
     */
    @PostMapping
    public ResponseEntity<ProjectResponse> createProject(@Valid @RequestBody CreateProjectRequest request) {
        Project project = projectService.createProject(
            request.getName(),
            request.getDescription(),
            request.getGenre(),
            SampleGroupType.valueOf(request.getSampleGroupType())
        );

        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ProjectResponse.from(project));
    }

    /**
     * 列出所有项目
     */
    @GetMapping
    public ResponseEntity<List<ProjectResponse>> listProjects() {
        List<Project> projects = projectService.listProjects();

        List<ProjectResponse> responses = projects.stream()
            .map(ProjectResponse::from)
            .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    /**
     * 获取项目详情
     */
    @GetMapping("/{projectId}")
    public ResponseEntity<ProjectResponse> getProject(@PathVariable String projectId) {
        Project project = projectService.getProject(projectId);
        return ResponseEntity.ok(ProjectResponse.from(project));
    }

    /**
     * 删除（归档）项目
     */
    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> deleteProject(@PathVariable String projectId) {
        projectService.deleteProject(projectId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{projectId}/members")
    public ResponseEntity<List<Map<String, Object>>> listProjectMembers(@PathVariable String projectId) {
        projectService.getProject(projectId);
        return ResponseEntity.ok(projectAccessService.listProjectMembers(projectId));
    }

    @PostMapping("/{projectId}/members")
    public ResponseEntity<Map<String, Object>> grantProjectMember(
            @PathVariable String projectId,
            @RequestBody Map<String, Object> request) {
        projectService.getProject(projectId);
        String userId = stringValue(request == null ? null : request.get("userId"), "");
        String actor = stringValue(request == null ? null : request.get("actor"), userId);
        String organizationId = stringValue(request == null ? null : request.get("organizationId"), RequestAccessContextHolder.current().organizationId());
        String role = stringValue(request == null ? null : request.get("role"), "viewer");
        return ResponseEntity.status(HttpStatus.CREATED).body(projectAccessService.toResponse(
            projectAccessService.grantProjectAccess(
                projectId,
                userId,
                actor,
                organizationId,
                role,
                RequestAccessContextHolder.current().actor()
            )
        ));
    }

    @DeleteMapping("/{projectId}/members/{userId}")
    public ResponseEntity<Map<String, Object>> revokeProjectMember(
            @PathVariable String projectId,
            @PathVariable String userId) {
        projectService.getProject(projectId);
        return ResponseEntity.ok(projectAccessService.toResponse(
            projectAccessService.revokeProjectAccess(projectId, userId, RequestAccessContextHolder.current().actor())
        ));
    }

    private String stringValue(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value).trim();
    }
}
