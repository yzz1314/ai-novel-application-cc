package com.novel.system.controller;

import com.novel.system.dto.response.SkillResponse;
import com.novel.system.service.SkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;

    @GetMapping
    public ResponseEntity<List<SkillResponse>> listSkills(@PathVariable String projectId) {
        return ResponseEntity.ok(skillService.listSkills(projectId));
    }

    @GetMapping("/enabled")
    public ResponseEntity<Map<String, Object>> getEnabledConfig(@PathVariable String projectId) {
        return ResponseEntity.ok(skillService.getEnabledConfig(projectId));
    }

    @GetMapping("/profile")
    public ResponseEntity<Map<String, Object>> getSkillProfile(@PathVariable String projectId) {
        return ResponseEntity.ok(skillService.getSkillProfile(projectId));
    }

    @PostMapping("/profile/sync")
    public ResponseEntity<Map<String, Object>> syncSkillProfile(@PathVariable String projectId) {
        return ResponseEntity.ok(skillService.syncSkillProfile(projectId));
    }

    @GetMapping("/enabled/versions")
    public ResponseEntity<List<Map<String, Object>>> listSkillConfigVersions(@PathVariable String projectId) {
        return ResponseEntity.ok(skillService.listSkillConfigVersions(projectId));
    }

    @PostMapping("/enabled/versions/{versionId}/restore")
    public ResponseEntity<Map<String, Object>> restoreSkillConfigVersion(
            @PathVariable String projectId,
            @PathVariable String versionId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(skillService.restoreSkillConfigVersion(projectId, versionId, request));
    }

    @GetMapping("/conflicts")
    public ResponseEntity<Map<String, Object>> detectConflicts(@PathVariable String projectId) {
        return ResponseEntity.ok(skillService.detectConflicts(projectId));
    }

    @GetMapping("/{skillName}")
    public ResponseEntity<SkillResponse> getSkill(
            @PathVariable String projectId,
            @PathVariable String skillName) {
        return ResponseEntity.ok(skillService.getSkill(projectId, skillName));
    }

    @GetMapping("/{skillName}/versions")
    public ResponseEntity<List<Map<String, Object>>> listSkillVersions(
            @PathVariable String projectId,
            @PathVariable String skillName) {
        return ResponseEntity.ok(skillService.listSkillVersions(projectId, skillName));
    }

    @PostMapping("/{skillName}/quality-check")
    public ResponseEntity<Map<String, Object>> validateSkillQuality(
            @PathVariable String projectId,
            @PathVariable String skillName,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(skillService.validateSkillQuality(projectId, skillName, request));
    }

    @PostMapping("/{skillName}/approve")
    public ResponseEntity<Map<String, Object>> approveSkill(
            @PathVariable String projectId,
            @PathVariable String skillName,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(skillService.approveSkill(projectId, skillName, request));
    }

    @PostMapping("/{skillName}/reject")
    public ResponseEntity<Map<String, Object>> rejectSkill(
            @PathVariable String projectId,
            @PathVariable String skillName,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(skillService.rejectSkill(projectId, skillName, request));
    }

    @GetMapping("/{skillName}/versions/{versionId}")
    public ResponseEntity<Map<String, Object>> getSkillVersion(
            @PathVariable String projectId,
            @PathVariable String skillName,
            @PathVariable String versionId) {
        return ResponseEntity.ok(skillService.getSkillVersion(projectId, skillName, versionId));
    }

    @PostMapping("/{skillName}/versions/{versionId}/restore")
    public ResponseEntity<Map<String, Object>> restoreSkillVersion(
            @PathVariable String projectId,
            @PathVariable String skillName,
            @PathVariable String versionId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(skillService.restoreSkillVersion(projectId, skillName, versionId, request));
    }

    @PatchMapping("/{skillName}")
    public ResponseEntity<SkillResponse> updateSkill(
            @PathVariable String projectId,
            @PathVariable String skillName,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(skillService.updateSkill(projectId, skillName, request));
    }

    @PatchMapping("/{skillName}/config")
    public ResponseEntity<SkillResponse> updateSkillConfig(
            @PathVariable String projectId,
            @PathVariable String skillName,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(skillService.updateSkillConfig(projectId, skillName, request));
    }

    @PostMapping("/{skillName}/enable")
    public ResponseEntity<SkillResponse> enableSkill(
            @PathVariable String projectId,
            @PathVariable String skillName) {
        return ResponseEntity.ok(skillService.updateSkillConfig(projectId, skillName, Map.of("enabled", true)));
    }

    @PostMapping("/{skillName}/disable")
    public ResponseEntity<SkillResponse> disableSkill(
            @PathVariable String projectId,
            @PathVariable String skillName) {
        return ResponseEntity.ok(skillService.updateSkillConfig(projectId, skillName, Map.of("enabled", false)));
    }
}
