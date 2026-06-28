package com.novel.system.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardAlertDeliveryServiceTest {

    @Mock
    private RestTemplate restTemplate;
    @Mock
    private JavaMailSender mailSender;
    @Mock
    private Environment environment;

    private DashboardAlertDeliveryService deliveryService;

    @BeforeEach
    void setUp() {
        deliveryService = new DashboardAlertDeliveryService(restTemplate, mailSender, environment);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deliversConfiguredWebhook() {
        when(environment.getProperty("dashboard.alerts.webhook-url")).thenReturn("https://example.invalid/hook");
        when(environment.getProperty("dashboard.alerts.email-to")).thenReturn("");
        when(restTemplate.postForEntity(eq("https://example.invalid/hook"), any(), eq(String.class)))
            .thenReturn(new ResponseEntity<>("ok", HttpStatus.OK));

        Map<String, Object> receipt = deliveryService.deliver(payload(), "ESCALATE");

        assertThat(receipt)
            .containsEntry("status", "DELIVERED")
            .containsEntry("configured", true)
            .containsEntry("delivered", true);
        assertThat((Map<String, Object>) receipt.get("webhook"))
            .containsEntry("status", "DELIVERED")
            .containsEntry("httpStatus", 200);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deliversConfiguredEmail() {
        when(environment.getProperty("dashboard.alerts.webhook-url")).thenReturn("");
        when(environment.getProperty("dashboard.alerts.email-to")).thenReturn("ops@example.invalid");
        when(environment.getProperty("dashboard.alerts.email-from")).thenReturn("system@example.invalid");

        Map<String, Object> receipt = deliveryService.deliver(payload(), "NOTIFY");

        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(message.capture());
        assertThat(message.getValue().getTo()).containsExactly("ops@example.invalid");
        assertThat(message.getValue().getFrom()).isEqualTo("system@example.invalid");
        assertThat((Map<String, Object>) receipt.get("email"))
            .containsEntry("status", "DELIVERED")
            .containsEntry("recipients", "ops@example.invalid");
        assertThat(receipt).containsEntry("status", "DELIVERED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void skipsWhenNoExternalChannelConfigured() {
        when(environment.getProperty("dashboard.alerts.webhook-url")).thenReturn("");
        when(environment.getProperty("dashboard.alerts.email-to")).thenReturn("");

        Map<String, Object> receipt = deliveryService.deliver(payload(), "NOTIFY");

        assertThat(receipt)
            .containsEntry("status", "SKIPPED")
            .containsEntry("configured", false)
            .containsEntry("delivered", false);
        assertThat((Map<String, Object>) receipt.get("webhook")).containsEntry("status", "SKIPPED");
        assertThat((Map<String, Object>) receipt.get("email")).containsEntry("status", "SKIPPED");
    }

    private Map<String, Object> payload() {
        return Map.of(
            "title", "Failed tasks",
            "severity", "critical",
            "target", "tasks",
            "message", "Task failed",
            "conditionKey", "failed_tasks:1",
            "details", Map.of("failedTasks", 1)
        );
    }
}
