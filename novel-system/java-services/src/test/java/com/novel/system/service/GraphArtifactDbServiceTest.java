package com.novel.system.service;

import com.novel.system.entity.GraphArtifact;
import com.novel.system.entity.Project;
import com.novel.system.repository.GraphArtifactRepository;
import com.novel.system.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GraphArtifactDbServiceTest {

    private static final String PROJECT_ID = "project_graph_db_analysis";

    @TempDir
    Path tempDir;

    @Mock
    private ProjectService projectService;

    @Mock
    private GraphArtifactRepository graphArtifactRepository;

    @Mock
    private TaskRepository taskRepository;

    private GraphArtifactDbService graphArtifactDbService;
    private final AtomicReference<GraphArtifact> savedArtifact = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        graphArtifactDbService = new GraphArtifactDbService(projectService, graphArtifactRepository, taskRepository);
        ReflectionTestUtils.setField(graphArtifactDbService, "basePath", tempDir.toString());

        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("Graph DB Analysis Test");
        when(projectService.getProject(PROJECT_ID)).thenReturn(project);
        when(taskRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of());
        when(graphArtifactRepository.findByProjectIdAndBookId(PROJECT_ID, "default")).thenReturn(Optional.empty());
        when(graphArtifactRepository.save(any(GraphArtifact.class))).thenAnswer(invocation -> {
            GraphArtifact artifact = invocation.getArgument(0);
            savedArtifact.set(artifact);
            return artifact;
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void syncGraphPersistsAdvancedAnalysisAndIncrementalSummary() throws Exception {
        writeProjectFile("graph/default_graph.json", """
            {
              "graph_id": "graph_advanced",
              "nodes": [
                {"node_id": "char_lin", "name": "Lin", "node_type": "character"},
                {"node_id": "char_su", "name": "Su", "node_type": "character"},
                {"node_id": "loc_city", "name": "City", "node_type": "location"}
              ],
              "edges": [
                {"edge_id": "edge_1", "source_id": "char_lin", "target_id": "char_su", "edge_type": "ally"},
                {"edge_id": "edge_2", "source_id": "char_su", "target_id": "loc_city", "edge_type": "appears_in"}
              ],
              "statistics": {
                "connected_components": 1,
                "largest_component_size": 3,
                "top_nodes_by_centrality": [
                  {"node_id": "char_su", "name": "Su", "node_type": "character", "degree_centrality": 1.0, "degree": 2}
                ],
                "top_nodes_by_betweenness": [
                  {"node_id": "char_su", "name": "Su", "node_type": "character", "betweenness_centrality": 0.5}
                ]
              },
              "analysis": {
                "relationship_analysis": [
                  {"edge_type": "ally", "count": 1, "density": 0.33}
                ],
                "key_paths": [
                  {"source": "char_lin", "target": "loc_city", "length": 2, "path": ["char_lin", "char_su", "loc_city"]}
                ],
                "incremental_build": {
                  "enabled": true,
                  "used_existing_graph": true,
                  "nodes_created": 1,
                  "nodes_updated": 1,
                  "nodes_preserved_from_previous": 1,
                  "edges_created": 1,
                  "edges_preserved_from_previous": 1,
                  "preserved_node_ids": ["legacy_secret"]
                },
                "warnings": []
              }
            }
            """);

        Map<String, Object> response = graphArtifactDbService.syncGraphFromWorkspace(PROJECT_ID, Map.of("book_id", "default"));

        assertThat(response)
            .containsEntry("graphId", "graph_advanced")
            .containsEntry("connectedComponents", 1)
            .containsEntry("largestComponentSize", 3)
            .containsEntry("topCentralityCount", 1)
            .containsEntry("relationshipAnalysisCount", 1)
            .containsEntry("keyPathCount", 1);
        List<Map<String, Object>> topNodesByCentrality = (List<Map<String, Object>>) response.get("topNodesByCentrality");
        assertThat(topNodesByCentrality).hasSize(1);
        assertThat(topNodesByCentrality.get(0))
            .containsEntry("node_id", "char_su")
            .containsEntry("degree_centrality", 1.0);
        List<Map<String, Object>> relationshipAnalysis = (List<Map<String, Object>>) response.get("relationshipAnalysis");
        assertThat(relationshipAnalysis).hasSize(1);
        assertThat(relationshipAnalysis.get(0)).containsEntry("edge_type", "ally");
        List<Map<String, Object>> keyPaths = (List<Map<String, Object>>) response.get("keyPaths");
        assertThat(keyPaths).hasSize(1);
        assertThat(keyPaths.get(0))
            .containsEntry("source", "char_lin")
            .containsEntry("target", "loc_city");

        Map<String, Object> incrementalSummary = (Map<String, Object>) response.get("incrementalSummary");
        assertThat(incrementalSummary)
            .containsEntry("enabled", true)
            .containsEntry("usedExistingGraph", true)
            .containsEntry("nodesCreated", 1)
            .containsEntry("nodesUpdated", 1)
            .containsEntry("nodesPreservedFromPrevious", 1)
            .containsEntry("edgesCreated", 1)
            .containsEntry("edgesPreservedFromPrevious", 1);
        assertThat((List<String>) incrementalSummary.get("preservedNodeIds")).containsExactly("legacy_secret");

        Map<String, Object> graphMetadata = (Map<String, Object>) response.get("graphMetadata");
        assertThat(graphMetadata)
            .containsEntry("topCentralityCount", 1)
            .containsEntry("relationshipAnalysisCount", 1)
            .containsEntry("keyPathCount", 1);

        GraphArtifact saved = savedArtifact.get();
        assertThat(saved.getTopNodesByCentrality()).hasSize(1);
        assertThat(saved.getRelationshipAnalysis()).hasSize(1);
        assertThat(saved.getKeyPaths()).hasSize(1);
        assertThat(saved.getIncrementalSummary()).containsEntry("usedExistingGraph", true);

        when(graphArtifactRepository.findByProjectIdOrderByUpdatedAtDesc(PROJECT_ID)).thenReturn(List.of(saved));
        Map<String, Object> summary = graphArtifactDbService.listGraphs(PROJECT_ID).get(0);
        assertThat(summary)
            .containsEntry("connectedComponents", 1)
            .containsEntry("topCentralityCount", 1)
            .containsEntry("relationshipAnalysisCount", 1)
            .containsEntry("keyPathCount", 1);
    }

    private Path projectRoot() {
        return tempDir.resolve("projects").resolve(PROJECT_ID);
    }

    private void writeProjectFile(String relativePath, String content) throws Exception {
        Path file = projectRoot().resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
