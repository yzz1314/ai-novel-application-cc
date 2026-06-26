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
import java.util.ArrayList;
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
        List<Project> projects = projectRepository.findAll();
        List<Project> recentProjects = projects.stream()
            .sorted((left, right) -> nullSafeTime(right.getUpdatedAt()).compareTo(nullSafeTime(left.getUpdatedAt())))
            .limit(6)
            .toList();
        List<Task> recentTasks = taskRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 8));
        Map<String, Object> stats = stats();
        Map<String, Object> taskSummary = taskSummary();
        List<Task> partialTasks = taskRepository.findByStatus(TaskStatus.PARTIAL);
        Map<String, Object> serviceStatus = serviceStatus();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("generatedAt", LocalDateTime.now());
        response.put("stats", stats);
        response.put("taskSummary", taskSummary);
        response.put("healthSummary", healthSummary(stats, taskSummary, partialTasks, serviceStatus));
        response.put("workflowSummary", workflowSummary(recentProjects));
        response.put("blockedProjects", blockedProjects(projects));
        response.put("nextActions", nextActions(stats, taskSummary, partialTasks, serviceStatus));
        response.put("recentProjects", recentProjects.stream().map(this::projectCard).toList());
        response.put("recentTasks", recentTasks.stream()
            .map(task -> TaskResponse.from(task, taskExecutorService.getTaskProgress(task)))
            .toList());
        response.put("serviceStatus", serviceStatus);
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

    private Map<String, Object> healthSummary(
            Map<String, Object> stats,
            Map<String, Object> taskSummary,
            List<Task> partialTasks,
            Map<String, Object> serviceStatus) {
        long activeTasks = taskCount(taskSummary, TaskStatus.PENDING) + taskCount(taskSummary, TaskStatus.RUNNING);
        long failedTasks = taskCount(taskSummary, TaskStatus.FAILED);
        long waitingApprovals = partialTasks.stream().filter(this::isWaitingForHuman).count();
        long completedTasks = taskCount(taskSummary, TaskStatus.SUCCESS)
            + taskCount(taskSummary, TaskStatus.PARTIAL)
            + taskCount(taskSummary, TaskStatus.FAILED)
            + taskCount(taskSummary, TaskStatus.CANCELLED);
        long successfulTasks = taskCount(taskSummary, TaskStatus.SUCCESS);
        int successRate = completedTasks > 0 ? Math.round(successfulTasks * 100f / completedTasks) : 0;
        boolean pythonUp = serviceIsUp(serviceStatus.get("python"));

        String status;
        String message;
        if (!pythonUp) {
            status = "DEGRADED";
            message = "Python AI 服务不可用，新的 Agent 任务可能失败";
        } else if (failedTasks > 0) {
            status = "ATTENTION";
            message = "存在失败任务，建议优先查看诊断并重试";
        } else if (waitingApprovals > 0) {
            status = "WAITING_APPROVAL";
            message = "有工作流等待人工确认";
        } else if (activeTasks > 0) {
            status = "BUSY";
            message = "任务正在运行，生产线处于工作中";
        } else {
            status = "HEALTHY";
            message = "服务正常，暂无阻塞任务";
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", status);
        summary.put("message", message);
        summary.put("activeTasks", activeTasks);
        summary.put("failedTasks", failedTasks);
        summary.put("waitingApprovals", waitingApprovals);
        summary.put("partialTasks", taskCount(taskSummary, TaskStatus.PARTIAL));
        summary.put("successRate", successRate);
        summary.put("activeProjects", stats.get("activeProjects"));
        summary.put("generatedAt", LocalDateTime.now());
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

    private List<Map<String, Object>> blockedProjects(List<Project> projects) {
        return projects.stream()
            .map(this::projectBlocker)
            .filter(item -> item != null)
            .limit(6)
            .toList();
    }

    private Map<String, Object> projectBlocker(Project project) {
        long failedTasks = taskRepository.countByProjectIdAndStatus(project.getId(), TaskStatus.FAILED);
        long partialTasks = taskRepository.countByProjectIdAndStatus(project.getId(), TaskStatus.PARTIAL);
        List<Task> recentPartialTasks = taskRepository.findByProjectIdAndStatusOrderByCreatedAtDesc(
            project.getId(),
            TaskStatus.PARTIAL,
            PageRequest.of(0, 20)
        );
        long waitingApprovals = recentPartialTasks.stream().filter(this::isWaitingForHuman).count();
        if (failedTasks == 0 && waitingApprovals == 0 && partialTasks == 0) {
            return null;
        }

        List<Task> recentTasks = taskRepository.findByProjectIdOrderByCreatedAtDesc(
            project.getId(),
            PageRequest.of(0, 1)
        );
        String blocker = failedTasks > 0
            ? "FAILED_TASK"
            : waitingApprovals > 0 ? "WAITING_APPROVAL" : "PARTIAL_TASK";

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("project", ProjectResponse.from(project));
        item.put("blocker", blocker);
        item.put("failedTasks", failedTasks);
        item.put("partialTasks", partialTasks);
        item.put("waitingApprovals", waitingApprovals);
        item.put("latestTask", recentTasks.isEmpty()
            ? null
            : TaskResponse.from(recentTasks.get(0), taskExecutorService.getTaskProgress(recentTasks.get(0))));
        return item;
    }

    private List<Map<String, Object>> nextActions(
            Map<String, Object> stats,
            Map<String, Object> taskSummary,
            List<Task> partialTasks,
            Map<String, Object> serviceStatus) {
        List<Map<String, Object>> actions = new ArrayList<>();
        long failedTasks = taskCount(taskSummary, TaskStatus.FAILED);
        long activeTasks = taskCount(taskSummary, TaskStatus.PENDING) + taskCount(taskSummary, TaskStatus.RUNNING);
        long waitingApprovals = partialTasks.stream().filter(this::isWaitingForHuman).count();

        if (!serviceIsUp(serviceStatus.get("python"))) {
            actions.add(action("检查 Python 服务", "python-service", "Python AI 服务当前不可用，先恢复服务再启动新任务"));
        }
        if (failedTasks > 0) {
            actions.add(action("处理失败任务", "tasks", "进入任务中心查看错误、日志和 checkpoint 后重试"));
        }
        if (waitingApprovals > 0) {
            actions.add(action("审批工作流", "tasks", "有工作流停在人工确认节点，批准后才能继续执行"));
        }
        if (numberValue(stats.get("totalProjects")) == 0) {
            actions.add(action("创建项目", "projects", "创建第一个长篇项目并上传样本"));
        }
        if (activeTasks > 0) {
            actions.add(action("查看运行任务", "tasks", "跟踪当前运行或排队任务的进度"));
        }
        if (actions.isEmpty()) {
            actions.add(action("推进最近项目", "projects", "从最近项目进入样本分析、Skill、大纲或章节生产"));
        }
        return actions.stream().limit(4).toList();
    }

    private Map<String, Object> action(String title, String target, String description) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("title", title);
        action.put("target", target);
        action.put("description", description);
        return action;
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

    private long taskCount(Map<String, Object> summary, TaskStatus status) {
        return numberValue(summary.get(status.name()));
    }

    private long numberValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private boolean isWaitingForHuman(Task task) {
        Object waiting = task.getResult() != null ? task.getResult().get("waiting_for_human") : null;
        return waiting instanceof Map<?, ?> waitingMap && waitingMap.get("node_id") != null;
    }

    private boolean serviceIsUp(Object service) {
        return service instanceof Map<?, ?> map && "UP".equals(String.valueOf(map.get("status")));
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
