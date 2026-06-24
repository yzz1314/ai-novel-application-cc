package com.novel.system.controller;

import com.novel.system.dto.response.SampleResponse;
import com.novel.system.dto.response.TaskResponse;
import com.novel.system.entity.Sample;
import com.novel.system.entity.Task;
import com.novel.system.service.SampleService;
import com.novel.system.service.TaskExecutorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/samples")
@RequiredArgsConstructor
public class SampleController {

    private final SampleService sampleService;
    private final TaskExecutorService taskExecutorService;

    /**
     * 上传样本文件
     */
    @PostMapping
    public ResponseEntity<SampleResponse> uploadSample(
            @PathVariable String projectId,
            @RequestParam("file") MultipartFile file) {

        // 1. 上传文件
        Sample sample = sampleService.uploadSample(projectId, file);

        // 2. 创建文本规范化任务
        Map<String, Object> inputRefs = Map.of("sample_id", sample.getId());
        Task task = taskExecutorService.createTask(
            projectId,
            "sample_import",
            "SampleImportAgent",
            inputRefs,
            new HashMap<>()
        );

        // 3. 异步执行任务
        taskExecutorService.executeTaskAsync(task.getId());

        return ResponseEntity.status(HttpStatus.CREATED)
            .body(SampleResponse.from(sample));
    }

    /**
     * 列出项目的所有样本
     */
    @GetMapping
    public ResponseEntity<List<SampleResponse>> listSamples(@PathVariable String projectId) {
        List<Sample> samples = sampleService.listSamplesByProject(projectId);

        List<SampleResponse> responses = samples.stream()
            .map(SampleResponse::from)
            .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    /**
     * 获取样本详情
     */
    @GetMapping("/{sampleId}")
    public ResponseEntity<SampleResponse> getSample(@PathVariable String sampleId) {
        Sample sample = sampleService.getSample(sampleId);
        return ResponseEntity.ok(SampleResponse.from(sample));
    }

    /**
     * 开始样本分析
     */
    @PostMapping("/analyze")
    public ResponseEntity<TaskResponse> analyzeSamples(@PathVariable String projectId) {

        // 创建全文拆解任务
        Map<String, Object> inputRefs = Map.of("project_id", projectId);
        Task task = taskExecutorService.createTask(
            projectId,
            "full_text_analysis",
            "FullTextAnalysisAgent",
            inputRefs,
            new HashMap<>()
        );

        // 异步执行任务
        taskExecutorService.executeTaskAsync(task.getId());

        return ResponseEntity.ok(TaskResponse.from(task));
    }
}
