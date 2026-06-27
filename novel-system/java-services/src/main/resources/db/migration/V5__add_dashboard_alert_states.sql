CREATE TABLE IF NOT EXISTS dashboard_alert_states (
    alert_id varchar(120) PRIMARY KEY,
    status varchar(32) NOT NULL,
    actor varchar(120),
    note varchar(500),
    acknowledged_at timestamp,
    snoozed_until timestamp,
    metadata jsonb,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_dashboard_alert_states_status ON dashboard_alert_states(status);
CREATE INDEX IF NOT EXISTS idx_dashboard_alert_states_snoozed_until ON dashboard_alert_states(snoozed_until);
