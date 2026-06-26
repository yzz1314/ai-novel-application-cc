package com.novel.system.service;

import com.novel.system.entity.Project;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GraphArtifactServiceTest {

    private static final String PROJECT_ID = "project_graph_cache";

    @TempDir
    Path tempDir;

    @Mock
    private ProjectService projectService;

    @Mock
    private TaskExecutorService taskExecutorService;

    private GraphArtifactService graphArtifactService;

    @BeforeEach
    void setUp() {
        graphArtifactService = new GraphArtifactService(projectService, taskExecutorService);
        ReflectionTestUtils.setField(graphArtifactService, "basePath", tempDir.toString());

        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("Graph Cache Test");
        when(projectService.getProject(PROJECT_ID)).thenReturn(project);
    }

    @Test
    void queryGraphWritesAndReusesCache() throws Exception {
        writeProjectFile("graph/default_graph.json", """
            {
              "graph_id": "graph_default",
              "nodes": [
                {"node_id": "char_lin", "name": "Lin", "node_type": "character"},
                {"node_id": "loc_city", "name": "City", "node_type": "location"}
              ],
              "edges": [
                {"edge_id": "edge_1", "source_id": "char_lin", "target_id": "loc_city", "edge_type": "appears_in"}
              ]
            }
            """);

        Map<String, Object> request = Map.of(
            "queryType", "subgraph",
            "centerNode", "char_lin",
            "radius", 1
        );
        Map<String, Object> first = graphArtifactService.queryGraph(PROJECT_ID, "default", request);
        Map<String, Object> second = graphArtifactService.queryGraph(PROJECT_ID, "default", request);

        assertThat(first)
            .containsEntry("cacheHit", false)
            .containsKey("cachePath");
        assertThat(second)
            .containsEntry("cacheHit", true)
            .containsEntry("cacheId", first.get("cacheId"));
        String cachePath = String.valueOf(first.get("cachePath"));
        String cacheJson = Files.readString(projectRoot().resolve(cachePath), StandardCharsets.UTF_8);
        assertThat(cacheJson)
            .contains("\"bookId\" : \"default\"")
            .contains("\"queryType\" : \"subgraph\"")
            .contains("\"response\"");

        Map<String, Object> listed = graphArtifactService.listQueryCaches(PROJECT_ID, "default");
        assertThat(listed.get("count")).isEqualTo(1);

        Map<String, Object> cleared = graphArtifactService.clearQueryCaches(PROJECT_ID, "default");
        assertThat(cleared)
            .containsEntry("status", "cleared")
            .containsEntry("deletedCount", 1);
        assertThat(Files.exists(projectRoot().resolve(cachePath))).isFalse();
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
