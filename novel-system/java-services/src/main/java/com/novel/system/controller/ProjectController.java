package com.novel.system.controller;

import com.novel.system.dto.request.CreateProjectRequest;
import com.novel.system.dto.response.ProjectResponse;
import com.novel.system.entity.Project;
import com.novel.system.entity.Project.SampleGroupType;
import com.novel.system.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

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
}
