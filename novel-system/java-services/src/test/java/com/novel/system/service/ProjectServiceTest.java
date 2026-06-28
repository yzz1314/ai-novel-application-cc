package com.novel.system.service;

import com.novel.system.dto.response.ProjectResponse;
import com.novel.system.entity.Project;
import com.novel.system.entity.Project.SampleGroupType;
import com.novel.system.repository.ProjectRepository;
import com.novel.system.security.RequestAccessContext;
import com.novel.system.security.RequestAccessContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectAccessService projectAccessService;

    private ProjectService projectService;

    @BeforeEach
    void setUp() {
        RequestAccessContextHolder.set(new RequestAccessContext(
            "creator-1",
            "Creator",
            "org-1",
            RequestAccessContext.normalizeRoles("owner"),
            RequestAccessContext.normalizeProjectIds("*"),
            true
        ));
        projectService = new ProjectService(projectRepository, projectAccessService);
        ReflectionTestUtils.setField(projectService, "basePath", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        RequestAccessContextHolder.clear();
    }

    @Test
    void createProjectPersistsGenreAndReturnsItInResponse() {
        when(projectRepository.existsByName("Genre Project")).thenReturn(false);
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            project.setId("project_genre");
            return project;
        });

        Project project = projectService.createProject(
            "Genre Project",
            "project with genre metadata",
            "  东方玄幻  ",
            SampleGroupType.SAME_GENRE
        );

        assertThat(project.getGenre()).isEqualTo("东方玄幻");
        assertThat(Files.exists(tempDir.resolve("projects").resolve("project_genre"))).isTrue();

        ProjectResponse response = ProjectResponse.from(project);
        assertThat(response.getGenre()).isEqualTo("东方玄幻");
        assertThat(response.getSampleGroupType()).isEqualTo("SAME_GENRE");
        verify(projectAccessService).grantProjectAccess(
            "project_genre",
            "creator-1",
            "Creator",
            "org-1",
            "owner",
            "Creator"
        );
    }
}
