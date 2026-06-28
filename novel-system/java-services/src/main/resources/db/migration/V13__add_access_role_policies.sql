CREATE TABLE IF NOT EXISTS access_role_policies (
    action_key varchar(128) PRIMARY KEY,
    description varchar(512),
    allowed_roles varchar(1024) NOT NULL,
    status varchar(32) NOT NULL,
    updated_by varchar(128),
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_access_role_policies_status
    ON access_role_policies (status);
