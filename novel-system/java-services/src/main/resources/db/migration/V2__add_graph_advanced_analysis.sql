ALTER TABLE graph_artifacts
    ADD COLUMN IF NOT EXISTS top_nodes_by_centrality jsonb,
    ADD COLUMN IF NOT EXISTS top_nodes_by_betweenness jsonb,
    ADD COLUMN IF NOT EXISTS relationship_analysis jsonb,
    ADD COLUMN IF NOT EXISTS key_paths jsonb,
    ADD COLUMN IF NOT EXISTS incremental_summary jsonb,
    ADD COLUMN IF NOT EXISTS graph_analysis jsonb;
