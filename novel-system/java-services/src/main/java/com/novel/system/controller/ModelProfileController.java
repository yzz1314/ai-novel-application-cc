package com.novel.system.controller;

import com.novel.system.service.ModelProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/model-profiles")
@RequiredArgsConstructor
public class ModelProfileController {

    private final ModelProfileService modelProfileService;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listProfiles() {
        return ResponseEntity.ok(modelProfileService.listProfiles());
    }

    @GetMapping("/default")
    public ResponseEntity<Map<String, Object>> getDefaultProfile() {
        Map<String, Object> profile = modelProfileService.getDefaultProfile();
        return ResponseEntity.ok(profile == null ? Map.of() : profile);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createProfile(@RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(modelProfileService.createProfile(request));
    }

    @GetMapping("/{profileId}")
    public ResponseEntity<Map<String, Object>> getProfile(@PathVariable String profileId) {
        return ResponseEntity.ok(modelProfileService.getProfile(profileId));
    }

    @PatchMapping("/{profileId}")
    public ResponseEntity<Map<String, Object>> updateProfile(
            @PathVariable String profileId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(modelProfileService.updateProfile(profileId, request));
    }

    @DeleteMapping("/{profileId}")
    public ResponseEntity<Map<String, Object>> deleteProfile(@PathVariable String profileId) {
        return ResponseEntity.ok(modelProfileService.deleteProfile(profileId));
    }

    @PostMapping("/{profileId}/default")
    public ResponseEntity<Map<String, Object>> setDefaultProfile(@PathVariable String profileId) {
        return ResponseEntity.ok(modelProfileService.setDefaultProfile(profileId));
    }

    @PostMapping("/{profileId}/test")
    public ResponseEntity<Map<String, Object>> testProfile(
            @PathVariable String profileId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(modelProfileService.testProfile(profileId, request));
    }
}
