package com.novel.system.service;

import com.novel.system.entity.Project;
import com.novel.system.entity.Project.ProjectStatus;
import com.novel.system.entity.Project.SampleGroupType;
import com.novel.system.repository.ProjectRepository;
import com.novel.system.exception.ResourceNotFoundException;
import com.novel.system.security.RequestAccessContext;
import com.novel.system.security.RequestAccessContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectAccessService projectAccessService;

    @Value("${file.storage.base-path:/workspace}")
    private String basePath;

    @Transactional
    public Project createProject(String name, String description, SampleGroupType sampleGroupType) {
        return createProject(name, description, null, sampleGroupType);
    }

    @Transactional
    public Project createProject(String name, String description, String genre, SampleGroupType sampleGroupType) {
        log.info("Creating project: {}", name);

        // 检查项目名称是否已存在
        if (projectRepository.existsByName(name)) {
            throw new IllegalArgumentException("项目名称已存在: " + name);
        }

        // 创建Project实体
        Project project = new Project();
        project.setName(name);
        project.setDescription(description);
        project.setGenre(normalizeBlank(genre));
        project.setSampleGroupType(sampleGroupType);
        project.setStatus(ProjectStatus.CREATED);

        // 保存到数据库（触发ID生成）
        project = projectRepository.save(project);
        grantOwnerAccess(project);

        // 创建项目目录结构
        try {
            createProjectDirectories(project.getId());
        } catch (IOException e) {
            log.error("Failed to create project directories", e);
            throw new RuntimeException("创建项目目录失败", e);
        }

        log.info("Project created with ID: {}", project.getId());
        return project;
    }

    private void grantOwnerAccess(Project project) {
        RequestAccessContext context = RequestAccessContextHolder.current();
        projectAccessService.grantProjectAccess(
            project.getId(),
            context.userId(),
            context.actor(),
            context.organizationId(),
            "owner",
            context.actor(),
            context
        );
    }

    private String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void createProjectDirectories(String projectId) throws IOException {
        Path projectRoot = Paths.get(basePath, "projects", projectId);

        // 创建目录结构
        String[] directories = {
            "samples/raw",
            "samples/normalized",
            "samples/chunks",
            "samples/manifests",
            "analysis/per_chunk",
            "analysis/per_book",
            "analysis/cross_book",
            "analysis/coverage",
            "skills/local",
            "novel/soul",
            "novel/outline",
            "novel/chapters/drafts",
            "novel/chapters/final",
            "novel/reviews",
            "memory/snapshots",
            "graph",
            "indexes/bm25",
            "indexes/vector",
            "logs",
            "checkpoints"
        };

        for (String dir : directories) {
            Path dirPath = projectRoot.resolve(dir);
            Files.createDirectories(dirPath);
        }

        // 创建记忆文件
        Path memoryDir = projectRoot.resolve("memory");
        Files.writeString(memoryDir.resolve("characters.md"), "# 人物状态记忆\n\n");
        Files.writeString(memoryDir.resolve("foreshadowing.md"), "# 伏笔记忆\n\n");
        Files.writeString(memoryDir.resolve("timeline.md"), "# 时间线记忆\n\n");
        Files.writeString(memoryDir.resolve("relationships.md"), "# 人物关系记忆\n\n");
        Files.writeString(memoryDir.resolve("cognition.md"), "# 角色认知记忆\n\n");
        Files.writeString(memoryDir.resolve("canon.md"), "# Canon正史规则\n\n");

        log.info("Created directory structure for project: {}", projectId);
    }

    public Project getProject(String projectId) {
        return projectRepository.findById(projectId)
            .orElseThrow(() -> new ResourceNotFoundException("项目不存在: " + projectId));
    }

    public List<Project> listProjects() {
        return projectRepository.findAll();
    }

    @Transactional
    public Project updateProjectStatus(String projectId, ProjectStatus newStatus) {
        Project project = getProject(projectId);
        project.setStatus(newStatus);
        return projectRepository.save(project);
    }

    @Transactional
    public void deleteProject(String projectId) {
        Project project = getProject(projectId);
        // 归档而不是物理删除
        project.setStatus(ProjectStatus.ARCHIVED);
        projectRepository.save(project);
        log.info("Project archived: {}", projectId);
    }
}
