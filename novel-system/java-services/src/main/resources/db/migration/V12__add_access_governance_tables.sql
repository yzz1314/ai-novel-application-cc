CREATE TABLE IF NOT EXISTS access_organization_members (
    id varchar(200) PRIMARY KEY,
    organization_id varchar(128) NOT NULL,
    user_id varchar(128) NOT NULL,
    role varchar(64) NOT NULL,
    status varchar(32) NOT NULL,
    granted_by varchar(128),
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    CONSTRAINT uk_access_org_members_org_user UNIQUE (organization_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_access_org_members_org_status
    ON access_organization_members (organization_id, status);

CREATE INDEX IF NOT EXISTS idx_access_org_members_user_status
    ON access_organization_members (user_id, status);

CREATE TABLE IF NOT EXISTS access_audit_events (
    id varchar(64) PRIMARY KEY,
    event_type varchar(128) NOT NULL,
    actor_id varchar(128),
    actor_name varchar(255),
    organization_id varchar(128),
    project_id varchar(64),
    target_user_id varchar(128),
    target_organization_id varchar(128),
    action varchar(128),
    outcome varchar(32) NOT NULL,
    reason varchar(1024),
    created_at timestamp NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_access_audit_events_created
    ON access_audit_events (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_access_audit_events_org_created
    ON access_audit_events (organization_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_access_audit_events_project_created
    ON access_audit_events (project_id, created_at DESC);
