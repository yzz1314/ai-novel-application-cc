CREATE TABLE IF NOT EXISTS dashboard_alert_notifications (
    id varchar(80) PRIMARY KEY,
    alert_id varchar(120) NOT NULL,
    condition_key varchar(240) NOT NULL,
    severity varchar(32),
    escalation_level varchar(32),
    status varchar(32),
    notification_count bigint,
    channels jsonb,
    payload jsonb,
    created_at timestamp NOT NULL,
    last_seen_at timestamp NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_dashboard_alert_notifications_dedupe
    ON dashboard_alert_notifications(alert_id, condition_key, escalation_level);
CREATE INDEX IF NOT EXISTS idx_dashboard_alert_notifications_last_seen
    ON dashboard_alert_notifications(last_seen_at DESC);
CREATE INDEX IF NOT EXISTS idx_dashboard_alert_notifications_level
    ON dashboard_alert_notifications(escalation_level);
