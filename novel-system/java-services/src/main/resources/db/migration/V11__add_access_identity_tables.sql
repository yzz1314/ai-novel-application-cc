CREATE TABLE IF NOT EXISTS access_organizations (
    id varchar(128) PRIMARY KEY,
    name varchar(255) NOT NULL,
    status varchar(32) NOT NULL,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL
);

CREATE TABLE IF NOT EXISTS access_users (
    id varchar(128) PRIMARY KEY,
    display_name varchar(255),
    organization_id varchar(128),
    status varchar(32) NOT NULL,
    last_seen_at timestamp,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_access_users_org_status
    ON access_users (organization_id, status);
