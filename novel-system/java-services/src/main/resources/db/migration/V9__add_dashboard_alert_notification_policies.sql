CREATE TABLE IF NOT EXISTS dashboard_alert_notification_policies (
    id varchar(80) PRIMARY KEY,
    enabled boolean NOT NULL DEFAULT true,
    default_group varchar(80),
    subscribers jsonb,
    routing_rules jsonb,
    templates jsonb,
    channels jsonb,
    updated_by varchar(120),
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL
);
