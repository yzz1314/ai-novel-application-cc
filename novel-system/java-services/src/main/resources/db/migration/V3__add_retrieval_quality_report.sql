ALTER TABLE retrieval_artifacts
    ADD COLUMN IF NOT EXISTS quality_report_path varchar(700),
    ADD COLUMN IF NOT EXISTS quality_report jsonb;
