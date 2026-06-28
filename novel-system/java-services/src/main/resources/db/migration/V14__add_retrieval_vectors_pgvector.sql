CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS retrieval_vectors (
    id varchar(220) PRIMARY KEY,
    project_id varchar(64) NOT NULL,
    doc_id varchar(160) NOT NULL,
    source_type varchar(80),
    path varchar(700),
    title varchar(500),
    snippet text,
    metadata jsonb,
    dimensions integer,
    embedding vector NOT NULL,
    updated_at timestamp NOT NULL DEFAULT now(),
    CONSTRAINT uk_retrieval_vectors_project_doc UNIQUE (project_id, doc_id)
);

CREATE INDEX IF NOT EXISTS idx_retrieval_vectors_project_id
    ON retrieval_vectors (project_id);

CREATE INDEX IF NOT EXISTS idx_retrieval_vectors_source_type
    ON retrieval_vectors (source_type);

CREATE INDEX IF NOT EXISTS idx_retrieval_vectors_updated_at
    ON retrieval_vectors (updated_at);
