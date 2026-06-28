package com.novel.system.service;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DashboardAlertDeliveryService {

    private final RestTemplate restTemplate;
    private final JavaMailSender mailSender;
    private final Environment environment;

    public Map<String, Object> deliver(Map<String, Object> payload, String escalationLevel) {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("attemptedAt", LocalDateTime.now().toString());
        receipt.put("escalationLevel", escalationLevel);

        Map<String, Object> webhookReceipt = deliverWebhook(payload, escalationLevel);
        Map<String, Object> emailReceipt = deliverEmail(payload, escalationLevel);
        receipt.put("webhook", webhookReceipt);
        receipt.put("email", emailReceipt);

        boolean delivered = delivered(webhookReceipt) || delivered(emailReceipt);
        boolean configured = configured(webhookReceipt) || configured(emailReceipt);
        receipt.put("configured", configured);
        receipt.put("delivered", delivered);
        receipt.put("status", delivered ? "DELIVERED" : configured ? "FAILED" : "SKIPPED");
        return receipt;
    }

    private Map<String, Object> deliverWebhook(Map<String, Object> payload, String escalationLevel) {
        String webhookUrl = configValue("dashboard.alerts.webhook-url", "DASHBOARD_ALERT_WEBHOOK_URL");
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("configured", !webhookUrl.isBlank());
        if (webhookUrl.isBlank()) {
            receipt.put("status", "SKIPPED");
            return receipt;
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("event", "dashboard_alert");
            body.put("escalationLevel", escalationLevel);
            body.put("payload", payload);
            ResponseEntity<String> response = restTemplate.postForEntity(webhookUrl, body, String.class);
            receipt.put("status", response.getStatusCode().is2xxSuccessful() ? "DELIVERED" : "FAILED");
            receipt.put("httpStatus", response.getStatusCode().value());
        } catch (Exception e) {
            receipt.put("status", "FAILED");
            receipt.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return receipt;
    }

    private Map<String, Object> deliverEmail(Map<String, Object> payload, String escalationLevel) {
        String to = configValue("dashboard.alerts.email-to", "DASHBOARD_ALERT_EMAIL_TO");
        String from = configValue("dashboard.alerts.email-from", "DASHBOARD_ALERT_EMAIL_FROM");
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("configured", !to.isBlank());
        if (to.isBlank()) {
            receipt.put("status", "SKIPPED");
            return receipt;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            if (!from.isBlank()) {
                message.setFrom(from);
            }
            message.setTo(to.split("\\s*,\\s*"));
            message.setSubject("[Novel System][" + escalationLevel + "] " + stringValue(payload.get("title"), "Dashboard alert"));
            message.setText(emailBody(payload));
            mailSender.send(message);
            receipt.put("status", "DELIVERED");
            receipt.put("recipients", to);
        } catch (Exception e) {
            receipt.put("status", "FAILED");
            receipt.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return receipt;
    }

    private String emailBody(Map<String, Object> payload) {
        return """
            Dashboard alert

            Title: %s
            Severity: %s
            Target: %s
            Message: %s
            Condition: %s
            Details: %s
            """.formatted(
            stringValue(payload.get("title"), "-"),
            stringValue(payload.get("severity"), "-"),
            stringValue(payload.get("target"), "-"),
            stringValue(payload.get("message"), "-"),
            stringValue(payload.get("conditionKey"), "-"),
            stringValue(payload.get("details"), "{}")
        );
    }

    private boolean delivered(Map<String, Object> receipt) {
        return "DELIVERED".equals(receipt.get("status"));
    }

    private boolean configured(Map<String, Object> receipt) {
        return Boolean.TRUE.equals(receipt.get("configured"));
    }

    private String configValue(String propertyName, String envName) {
        String value = environment == null ? null : environment.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            value = System.getenv(envName);
        }
        return value == null ? "" : value.trim();
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }
}
