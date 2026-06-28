package com.novel.system.controller;

import com.novel.system.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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

    @GetMapping("/trends")
    public ResponseEntity<Map<String, Object>> getTrends(@RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(dashboardService.getTrends(limit));
    }

    @GetMapping("/operations")
    public ResponseEntity<Map<String, Object>> getOperations() {
        return ResponseEntity.ok(dashboardService.getOperations());
    }

    @GetMapping("/alerts/notifications")
    public ResponseEntity<Map<String, Object>> getAlertNotifications(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String deliveryStatus,
            @RequestParam(required = false) String escalationLevel,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String alertId,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until) {
        return ResponseEntity.ok(dashboardService.getAlertNotifications(
            limit,
            Map.of(
                "deliveryStatus", deliveryStatus == null ? "" : deliveryStatus,
                "escalationLevel", escalationLevel == null ? "" : escalationLevel,
                "severity", severity == null ? "" : severity,
                "status", status == null ? "" : status,
                "alertId", alertId == null ? "" : alertId,
                "since", since == null ? "" : since,
                "until", until == null ? "" : until
            )
        ));
    }

    @GetMapping("/alerts/notification-policy")
    public ResponseEntity<Map<String, Object>> getAlertNotificationPolicy() {
        return ResponseEntity.ok(dashboardService.getAlertNotificationPolicy());
    }

    @PostMapping("/alerts/notification-policy")
    public ResponseEntity<Map<String, Object>> updateAlertNotificationPolicy(
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(dashboardService.updateAlertNotificationPolicy(request == null ? Map.of() : request));
    }

    @PostMapping("/alerts/{alertId}/state")
    public ResponseEntity<Map<String, Object>> updateAlertState(
            @PathVariable String alertId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(dashboardService.updateAlertState(alertId, request == null ? Map.of() : request));
    }
}
