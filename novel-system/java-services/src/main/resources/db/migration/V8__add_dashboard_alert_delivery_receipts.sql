ALTER TABLE dashboard_alert_notifications
    ADD COLUMN IF NOT EXISTS delivery_status varchar(32),
    ADD COLUMN IF NOT EXISTS delivery_attempts bigint,
    ADD COLUMN IF NOT EXISTS last_delivery_at timestamp,
    ADD COLUMN IF NOT EXISTS next_retry_at timestamp,
    ADD COLUMN IF NOT EXISTS delivery_receipt jsonb;

CREATE INDEX IF NOT EXISTS idx_dashboard_alert_notifications_delivery_status
    ON dashboard_alert_notifications(delivery_status);
CREATE INDEX IF NOT EXISTS idx_dashboard_alert_notifications_next_retry
    ON dashboard_alert_notifications(next_retry_at);
