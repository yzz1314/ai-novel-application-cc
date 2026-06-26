CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS projects (
    id varchar(64) PRIMARY KEY,
    name varchar(255) NOT NULL,
    description text,
    genre varchar(120),
    sample_group_type varchar(20) NOT NULL,
    source_language varchar(10),
    target_language varchar(10),
    status varchar(20) NOT NULL,
    model_profile_id varchar(64),
    skill_profile_id varchar(64),
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL
);

CREATE TABLE IF NOT EXISTS samples (
    id varchar(64) PRIMARY KEY,
    project_id varchar(64) NOT NULL,
    title varchar(255),
    file_name varchar(255) NOT NULL,
    file_path varchar(500) NOT NULL,
    file_hash varchar(64) NOT NULL,
    file_size_bytes bigint NOT NULL,
    total_chars integer,
    total_chapters integer,
    status varchar(20) NOT NULL,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL
);

CREATE TABLE IF NOT EXISTS tasks (
    id varchar(64) PRIMARY KEY,
    project_id varchar(64) NOT NULL,
    task_type varchar(50) NOT NULL,
    agent_name varchar(100),
    status varchar(20) NOT NULL,
    input_refs jsonb,
    output_refs jsonb,
    parameters jsonb,
    result jsonb,
    errors jsonb,
    warnings jsonb,
    metrics jsonb,
    checkpoint_ref varchar(500),
    retry_count integer,
    max_retries integer,
    created_at timestamp NOT NULL,
    started_at timestamp,
    finished_at timestamp
);

CREATE TABLE IF NOT EXISTS model_profiles (
    profile_id varchar(64) PRIMARY KEY,
    profile_name varchar(255) NOT NULL,
    description text,
    enabled boolean NOT NULL,
    default_profile boolean NOT NULL,
    main_model jsonb NOT NULL,
    fast_model jsonb,
    embedding_model jsonb,
    rerank_model jsonb,
    fallback_models jsonb,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL
);

CREATE TABLE IF NOT EXISTS skill_profiles (
    id varchar(128) PRIMARY KEY,
    project_id varchar(64),
    name varchar(255) NOT NULL,
    description text,
    enabled_skills jsonb NOT NULL,
    task_overrides jsonb,
    skill_metadata jsonb,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    CONSTRAINT uk_skill_profiles_project_name UNIQUE (project_id, name)
);

CREATE TABLE IF NOT EXISTS sample_chapters (
    id varchar(128) PRIMARY KEY,
    sample_id varchar(64) NOT NULL,
    project_id varchar(64) NOT NULL,
    chapter_index integer NOT NULL,
    title varchar(255),
    start_offset integer,
    end_offset integer,
    char_count integer,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    CONSTRAINT uk_sample_chapter_index UNIQUE (sample_id, chapter_index)
);

CREATE TABLE IF NOT EXISTS sample_chunks (
    id varchar(128) PRIMARY KEY,
    sample_id varchar(64) NOT NULL,
    project_id varchar(64) NOT NULL,
    chunk_id varchar(64) NOT NULL,
    chunk_index integer,
    chapter_index integer,
    part_index integer,
    start_offset integer,
    end_offset integer,
    char_count integer,
    heading_path varchar(500),
    file_path varchar(700),
    processed boolean,
    analysis_path varchar(700),
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL
);

CREATE TABLE IF NOT EXISTS analysis_results (
    id varchar(160) PRIMARY KEY,
    project_id varchar(64) NOT NULL,
    sample_id varchar(64) NOT NULL,
    chunk_id varchar(64) NOT NULL,
    chunk_index integer,
    start_pos integer,
    end_pos integer,
    chapter_range varchar(255),
    status varchar(32),
    summary text,
    plot_function text,
    reader_hook text,
    character_count integer,
    scene_technique_count integer,
    prose_technique_count integer,
    outline_technique_count integer,
    appeal_point_count integer,
    analysis jsonb,
    raw_result jsonb,
    analysis_path varchar(700),
    analyzed_at timestamp,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    CONSTRAINT uk_analysis_result_chunk UNIQUE (sample_id, chunk_id)
);

CREATE TABLE IF NOT EXISTS outline_artifacts (
    id varchar(160) PRIMARY KEY,
    project_id varchar(64) NOT NULL,
    book_id varchar(96) NOT NULL,
    book_title varchar(255),
    genre varchar(128),
    target_word_count integer,
    total_volumes integer,
    total_chapters integer,
    chapters_with_boundary integer,
    status varchar(48),
    review_status varchar(48),
    latest_review_score integer,
    outline_path varchar(700),
    project_soul_path varchar(700),
    latest_review_path varchar(700),
    outline jsonb NOT NULL,
    project_soul text,
    latest_review jsonb,
    outline_metadata jsonb,
    synced_at timestamp,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    CONSTRAINT uk_outline_artifacts_project_book UNIQUE (project_id, book_id)
);

CREATE TABLE IF NOT EXISTS chapter_artifacts (
    id varchar(190) PRIMARY KEY,
    project_id varchar(64) NOT NULL,
    book_id varchar(96) NOT NULL,
    chapter_id varchar(128),
    volume_number integer NOT NULL,
    chapter_number integer NOT NULL,
    chapter_title varchar(255),
    stage varchar(24) NOT NULL,
    status varchar(48),
    review_status varchar(48),
    human_review_status varchar(48),
    version integer,
    word_count integer,
    quality_score double precision,
    needs_revision boolean,
    boundary_passed boolean,
    boundary_error_count integer,
    boundary_warning_count integer,
    chapter_path varchar(700),
    text_path varchar(700),
    context_pack_path varchar(700),
    source_draft_path varchar(700),
    final_path varchar(700),
    latest_review_path varchar(700),
    content text,
    chapter jsonb NOT NULL,
    boundary_check jsonb,
    revision_history jsonb,
    human_review_history jsonb,
    manual_edit_history jsonb,
    latest_review jsonb,
    chapter_metadata jsonb,
    chapter_created_at timestamp,
    chapter_updated_at timestamp,
    finalized_at timestamp,
    synced_at timestamp,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    CONSTRAINT uk_chapter_artifacts_project_book_chapter_stage
        UNIQUE (project_id, book_id, volume_number, chapter_number, stage)
);

CREATE TABLE IF NOT EXISTS memory_artifacts (
    id varchar(160) PRIMARY KEY,
    project_id varchar(64) NOT NULL,
    book_id varchar(96) NOT NULL,
    status varchar(48),
    character_count integer,
    world_setting_count integer,
    plot_count integer,
    suspense_count integer,
    timeline_event_count integer,
    markdown_count integer,
    snapshot_count integer,
    memory_path varchar(700),
    book_memory_path varchar(700),
    latest_snapshot_id varchar(160),
    latest_snapshot_path varchar(700),
    markdown_memories jsonb NOT NULL,
    memory_json jsonb NOT NULL,
    latest_snapshot jsonb,
    snapshots jsonb,
    latest_tasks jsonb,
    memory_metadata jsonb,
    synced_at timestamp,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    CONSTRAINT uk_memory_artifacts_project UNIQUE (project_id)
);

CREATE TABLE IF NOT EXISTS graph_artifacts (
    id varchar(200) PRIMARY KEY,
    project_id varchar(64) NOT NULL,
    book_id varchar(96) NOT NULL,
    status varchar(48),
    graph_id varchar(160),
    node_count integer,
    edge_count integer,
    character_count integer,
    location_count integer,
    organization_count integer,
    item_count integer,
    average_degree double precision,
    density double precision,
    graph_path varchar(700),
    graph_json jsonb NOT NULL,
    nodes jsonb,
    edges jsonb,
    statistics jsonb,
    node_type_distribution jsonb,
    edge_type_distribution jsonb,
    top_nodes_by_degree jsonb,
    latest_tasks jsonb,
    graph_metadata jsonb,
    synced_at timestamp,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    CONSTRAINT uk_graph_artifacts_project_book UNIQUE (project_id, book_id)
);

CREATE TABLE IF NOT EXISTS retrieval_artifacts (
    id varchar(160) PRIMARY KEY,
    project_id varchar(64) NOT NULL,
    status varchar(48),
    bm25_document_count integer,
    vector_document_count integer,
    hybrid_document_count integer,
    context_pack_count integer,
    config_path varchar(700),
    rebuild_report_path varchar(700),
    config jsonb,
    bm25_summary jsonb,
    vector_summary jsonb,
    hybrid_summary jsonb,
    rebuild_report jsonb,
    context_packs jsonb,
    latest_tasks jsonb,
    retrieval_metadata jsonb,
    synced_at timestamp,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    CONSTRAINT uk_retrieval_artifacts_project UNIQUE (project_id)
);

CREATE INDEX IF NOT EXISTS idx_samples_project_id ON samples(project_id);
CREATE INDEX IF NOT EXISTS idx_tasks_project_id ON tasks(project_id);
CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status);
CREATE INDEX IF NOT EXISTS idx_tasks_task_type ON tasks(task_type);
CREATE INDEX IF NOT EXISTS idx_model_profiles_default ON model_profiles(default_profile);
CREATE INDEX IF NOT EXISTS idx_model_profiles_enabled ON model_profiles(enabled);
CREATE INDEX IF NOT EXISTS idx_skill_profiles_project_id ON skill_profiles(project_id);
CREATE INDEX IF NOT EXISTS idx_skill_profiles_updated_at ON skill_profiles(updated_at);
CREATE INDEX IF NOT EXISTS idx_sample_chapters_sample_id ON sample_chapters(sample_id);
CREATE INDEX IF NOT EXISTS idx_sample_chapters_project_id ON sample_chapters(project_id);
CREATE INDEX IF NOT EXISTS idx_sample_chunks_sample_id ON sample_chunks(sample_id);
CREATE INDEX IF NOT EXISTS idx_sample_chunks_project_id ON sample_chunks(project_id);
CREATE INDEX IF NOT EXISTS idx_sample_chunks_processed ON sample_chunks(processed);
CREATE INDEX IF NOT EXISTS idx_analysis_results_project_id ON analysis_results(project_id);
CREATE INDEX IF NOT EXISTS idx_analysis_results_sample_id ON analysis_results(sample_id);
CREATE INDEX IF NOT EXISTS idx_analysis_results_chunk_id ON analysis_results(chunk_id);
CREATE INDEX IF NOT EXISTS idx_analysis_results_status ON analysis_results(status);
CREATE INDEX IF NOT EXISTS idx_outline_artifacts_project_id ON outline_artifacts(project_id);
CREATE INDEX IF NOT EXISTS idx_outline_artifacts_book_id ON outline_artifacts(book_id);
CREATE INDEX IF NOT EXISTS idx_outline_artifacts_updated_at ON outline_artifacts(updated_at);
CREATE INDEX IF NOT EXISTS idx_chapter_artifacts_project_id ON chapter_artifacts(project_id);
CREATE INDEX IF NOT EXISTS idx_chapter_artifacts_book_id ON chapter_artifacts(book_id);
CREATE INDEX IF NOT EXISTS idx_chapter_artifacts_chapter ON chapter_artifacts(book_id, volume_number, chapter_number);
CREATE INDEX IF NOT EXISTS idx_chapter_artifacts_stage ON chapter_artifacts(stage);
CREATE INDEX IF NOT EXISTS idx_chapter_artifacts_updated_at ON chapter_artifacts(updated_at);
CREATE INDEX IF NOT EXISTS idx_memory_artifacts_project_id ON memory_artifacts(project_id);
CREATE INDEX IF NOT EXISTS idx_memory_artifacts_book_id ON memory_artifacts(book_id);
CREATE INDEX IF NOT EXISTS idx_memory_artifacts_updated_at ON memory_artifacts(updated_at);
CREATE INDEX IF NOT EXISTS idx_graph_artifacts_project_id ON graph_artifacts(project_id);
CREATE INDEX IF NOT EXISTS idx_graph_artifacts_book_id ON graph_artifacts(book_id);
CREATE INDEX IF NOT EXISTS idx_graph_artifacts_updated_at ON graph_artifacts(updated_at);
CREATE INDEX IF NOT EXISTS idx_retrieval_artifacts_project_id ON retrieval_artifacts(project_id);
CREATE INDEX IF NOT EXISTS idx_retrieval_artifacts_updated_at ON retrieval_artifacts(updated_at);
