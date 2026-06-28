CREATE TABLE IF NOT EXISTS dashboard_metric_snapshots (
    id varchar(64) PRIMARY KEY,
    captured_at timestamp NOT NULL,
    health_status varchar(48),
    active_tasks bigint,
    failed_tasks bigint,
    waiting_approvals bigint,
    slow_task_count bigint,
    high_retry_task_count bigint,
    active_alert_count bigint,
    snoozed_alert_count bigint,
    acknowledged_alert_count bigint,
    total_tokens bigint,
    avg_duration_ms bigint,
    max_duration_ms bigint,
    metrics jsonb
);

CREATE INDEX IF NOT EXISTS idx_dashboard_metric_snapshots_captured_at
    ON dashboard_metric_snapshots(captured_at DESC);
CREATE INDEX IF NOT EXISTS idx_dashboard_metric_snapshots_health
    ON dashboard_metric_snapshots(health_status);
