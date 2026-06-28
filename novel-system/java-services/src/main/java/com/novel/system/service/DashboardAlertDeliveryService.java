package com.novel.system.service;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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
        boolean enabled = channelEnabled(payload, "webhook");
        receipt.put("enabled", enabled);
        receipt.put("configured", enabled && !webhookUrl.isBlank());
        if (!enabled) {
            receipt.put("status", "SKIPPED");
            return receipt;
        }
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
        List<String> recipients = emailRecipients(to, payload);
        Map<String, Object> receipt = new LinkedHashMap<>();
        boolean enabled = channelEnabled(payload, "email");
        receipt.put("enabled", enabled);
        receipt.put("configured", enabled && !recipients.isEmpty());
        if (!enabled) {
            receipt.put("status", "SKIPPED");
            return receipt;
        }
        if (recipients.isEmpty()) {
            receipt.put("status", "SKIPPED");
            return receipt;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            if (!from.isBlank()) {
                message.setFrom(from);
            }
            message.setTo(recipients.toArray(String[]::new));
            message.setSubject(emailSubject(payload, escalationLevel));
            message.setText(emailBody(payload));
            mailSender.send(message);
            receipt.put("status", "DELIVERED");
            receipt.put("recipients", recipients);
        } catch (Exception e) {
            receipt.put("status", "FAILED");
            receipt.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return receipt;
    }

    private boolean channelEnabled(Map<String, Object> payload, String channelName) {
        Object channels = payload == null ? null : payload.get("channels");
        if (channels instanceof Map<?, ?> channelMap && channelMap.get(channelName) instanceof Map<?, ?> config) {
            Object enabled = config.get("enabled");
            if (enabled instanceof Boolean bool) {
                return bool;
            }
            if (enabled instanceof String text) {
                return !"false".equalsIgnoreCase(text) && !"0".equals(text);
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private List<String> emailRecipients(String configuredRecipients, Map<String, Object> payload) {
        Stream<String> configured = configuredRecipients == null || configuredRecipients.isBlank()
            ? Stream.empty()
            : Arrays.stream(configuredRecipients.split("\\s*,\\s*"));
        Object routing = payload == null ? null : payload.get("routing");
        Stream<String> policyRecipients = Stream.empty();
        if (routing instanceof Map<?, ?> routingMap && routingMap.get("emails") instanceof List<?> emails) {
            policyRecipients = emails.stream().map(item -> stringValue(item, ""));
        }
        return Stream.concat(configured, policyRecipients)
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .distinct()
            .toList();
    }

    @SuppressWarnings("unchecked")
    private String emailSubject(Map<String, Object> payload, String escalationLevel) {
        Object template = payload == null ? null : payload.get("template");
        if (template instanceof Map<?, ?> map && map.get("subject") != null) {
            return stringValue(map.get("subject"), "Dashboard alert");
        }
        return "[Novel System][" + escalationLevel + "] " + stringValue(payload.get("title"), "Dashboard alert");
    }

    @SuppressWarnings("unchecked")
    private String emailBody(Map<String, Object> payload) {
        Object template = payload == null ? null : payload.get("template");
        if (template instanceof Map<?, ?> map && map.get("body") != null) {
            return stringValue(map.get("body"), "");
        }
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
