CREATE TABLE IF NOT EXISTS project_members (
    id varchar(160) PRIMARY KEY,
    project_id varchar(64) NOT NULL,
    user_id varchar(128) NOT NULL,
    actor varchar(128),
    organization_id varchar(128),
    role varchar(64) NOT NULL,
    granted_by varchar(128),
    status varchar(32) NOT NULL,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    CONSTRAINT uk_project_members_project_user UNIQUE (project_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_project_members_user_project
    ON project_members (user_id, project_id);

CREATE INDEX IF NOT EXISTS idx_project_members_project_status
    ON project_members (project_id, status);
