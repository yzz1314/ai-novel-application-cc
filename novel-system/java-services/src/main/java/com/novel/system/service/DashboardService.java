package com.novel.system.service;

import com.novel.system.dto.response.ProjectResponse;
import com.novel.system.dto.response.TaskResponse;
import com.novel.system.entity.Project;
import com.novel.system.entity.Project.ProjectStatus;
import com.novel.system.entity.Sample.SampleStatus;
import com.novel.system.entity.Task;
import com.novel.system.entity.Task.TaskStatus;
import com.novel.system.repository.ChapterArtifactRepository;
import com.novel.system.repository.GraphArtifactRepository;
import com.novel.system.repository.MemoryArtifactRepository;
import com.novel.system.repository.OutlineArtifactRepository;
import com.novel.system.repository.ProjectRepository;
import com.novel.system.repository.RetrievalArtifactRepository;
import com.novel.system.repository.SampleRepository;
import com.novel.system.repository.SkillProfileRepository;
import com.novel.system.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final ProjectRepository projectRepository;
    private final SampleRepository sampleRepository;
    private final TaskRepository taskRepository;
    private final ChapterArtifactRepository chapterArtifactRepository;
    private final SkillProfileRepository skillProfileRepository;
    private final OutlineArtifactRepository outlineArtifactRepository;
    private final MemoryArtifactRepository memoryArtifactRepository;
    private final GraphArtifactRepository graphArtifactRepository;
    private final RetrievalArtifactRepository retrievalArtifactRepository;
    private final PythonClientService pythonClientService;
    private final TaskExecutorService taskExecutorService;

    public Map<String, Object> getDashboard() {
        List<Project> recentProjects = projectRepository.findAll().stream()
            .sorted((left, right) -> nullSafeTime(right.getUpdatedAt()).compareTo(nullSafeTime(left.getUpdatedAt())))
            .limit(6)
            .toList();
        List<Task> recentTasks = taskRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 8));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("generatedAt", LocalDateTime.now());
        response.put("stats", stats());
        response.put("taskSummary", taskSummary());
        response.put("workflowSummary", workflowSummary(recentProjects));
        response.put("recentProjects", recentProjects.stream().map(this::projectCard).toList());
        response.put("recentTasks", recentTasks.stream()
            .map(task -> TaskResponse.from(task, taskExecutorService.getTaskProgress(task)))
            .toList());
        response.put("serviceStatus", serviceStatus());
        return response;
    }

    private Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalProjects", projectRepository.count());
        stats.put("activeProjects", projectRepository.findByStatusIn(List.of(
            ProjectStatus.CREATED,
            ProjectStatus.INGESTING,
            ProjectStatus.ANALYZED,
            ProjectStatus.OUTLINING,
            ProjectStatus.WRITING
        )).size());
        stats.put("archivedProjects", projectRepository.findByStatus(ProjectStatus.ARCHIVED).size());
        stats.put("totalSamples", sampleRepository.count());
        stats.put("analyzedSamples", sampleRepository.countByStatus(SampleStatus.ANALYZED));
        stats.put("totalChapters", chapterArtifactRepository.count());
        stats.put("draftChapters", chapterArtifactRepository.countByStage("draft"));
        stats.put("finalChapters", chapterArtifactRepository.countByStage("final"));
        stats.put("totalTasks", taskRepository.count());
        return stats;
    }

    private Map<String, Object> taskSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (TaskStatus status : TaskStatus.values()) {
            summary.put(status.name(), taskRepository.countByStatus(status));
        }
        return summary;
    }

    private List<Map<String, Object>> workflowSummary(List<Project> projects) {
        return projects.stream()
            .map(project -> {
                String projectId = project.getId();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("project", ProjectResponse.from(project));
                item.put("samples", sampleRepository.countByProjectId(projectId));
                item.put("analyzedSamples", sampleRepository.countByProjectIdAndStatus(projectId, SampleStatus.ANALYZED));
                item.put("skills", skillProfileRepository.countByProjectId(projectId));
                item.put("outlines", outlineArtifactRepository.countByProjectId(projectId));
                item.put("draftChapters", chapterArtifactRepository.countByProjectIdAndStage(projectId, "draft"));
                item.put("finalChapters", chapterArtifactRepository.countByProjectIdAndStage(projectId, "final"));
                item.put("memoryArtifacts", memoryArtifactRepository.countByProjectId(projectId));
                item.put("graphArtifacts", graphArtifactRepository.countByProjectId(projectId));
                item.put("retrievalArtifacts", retrievalArtifactRepository.countByProjectId(projectId));
                item.put("tasks", taskRepository.countByProjectId(projectId));
                item.put("failedTasks", taskRepository.countByProjectIdAndStatus(projectId, TaskStatus.FAILED));
                item.put("progress", projectProgress(item));
                return item;
            })
            .toList();
    }

    private Map<String, Object> projectCard(Project project) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("project", ProjectResponse.from(project));
        card.put("sampleCount", sampleRepository.countByProjectId(project.getId()));
        card.put("chapterCount", chapterArtifactRepository.countByProjectId(project.getId()));
        card.put("taskCount", taskRepository.countByProjectId(project.getId()));
        card.put("failedTaskCount", taskRepository.countByProjectIdAndStatus(project.getId(), TaskStatus.FAILED));
        return card;
    }

    private int projectProgress(Map<String, Object> item) {
        int passed = 0;
        passed += countPositive(item.get("samples"));
        passed += countPositive(item.get("analyzedSamples"));
        passed += countPositive(item.get("skills"));
        passed += countPositive(item.get("outlines"));
        passed += countPositive(item.get("draftChapters"));
        passed += countPositive(item.get("finalChapters"));
        passed += countPositive(item.get("memoryArtifacts"));
        passed += countPositive(item.get("graphArtifacts"));
        passed += countPositive(item.get("retrievalArtifacts"));
        return Math.round(passed * 100f / 9f);
    }

    private int countPositive(Object value) {
        return value instanceof Number number && number.longValue() > 0 ? 1 : 0;
    }

    private Map<String, Object> serviceStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("java", Map.of(
            "status", "UP",
            "service", "novel-system-java"
        ));
        boolean pythonHealthy = pythonClientService.checkHealth();
        status.put("python", Map.of(
            "status", pythonHealthy ? "UP" : "DOWN",
            "service", "python-ai-service"
        ));
        return status;
    }

    private LocalDateTime nullSafeTime(LocalDateTime value) {
        return value == null ? LocalDateTime.MIN : value;
    }
}
