package com.novel.system.controller;

import com.novel.system.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getDashboard() {
        return ResponseEntity.ok(dashboardService.getDashboard());
    }

    @PostMapping("/alerts/{alertId}/state")
    public ResponseEntity<Map<String, Object>> updateAlertState(
            @PathVariable String alertId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(dashboardService.updateAlertState(alertId, request == null ? Map.of() : request));
    }
}
