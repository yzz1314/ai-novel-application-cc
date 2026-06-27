package com.novel.system.service;

import com.novel.system.entity.Project;
import com.novel.system.entity.Task;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    @SuppressWarnings("unchecked")
    void graphVersionsCanBeCreatedListedReadAndAttachedBeforeRebuild() throws Exception {
        writeProjectFile("graph/default_graph.json", """
            {
              "graph_id": "graph_default",
              "nodes": [
                {"node_id": "char_lin", "name": "Lin", "node_type": "character"},
                {"node_id": "char_su", "name": "Su", "node_type": "character"},
                {"node_id": "loc_city", "name": "City", "node_type": "location"}
              ],
              "edges": [
                {"edge_id": "edge_1", "source_id": "char_lin", "target_id": "char_su", "edge_type": "ally"},
                {"edge_id": "edge_2", "source_id": "char_su", "target_id": "loc_city", "edge_type": "appears_in"}
              ]
            }
            """);

        Map<String, Object> created = graphArtifactService.createGraphVersion(PROJECT_ID, "default", Map.of(
            "reason", "manual_review",
            "actor", "tester",
            "note", "before editing relationships"
        ));

        assertThat(created)
            .containsEntry("bookId", "default")
            .containsEntry("reason", "manual_review")
            .containsEntry("actor", "tester")
            .containsEntry("nodeCount", 3)
            .containsEntry("edgeCount", 2)
            .containsKey("graph");
        String versionId = String.valueOf(created.get("id"));
        String versionPath = String.valueOf(created.get("path"));
        assertThat(versionId).startsWith("graph_default_");
        assertThat(versionPath).startsWith("graph/versions/default/");
        assertThat(Files.exists(projectRoot().resolve(versionPath))).isTrue();

        Map<String, Object> detail = graphArtifactService.getGraphVersion(PROJECT_ID, "default", versionId);
        assertThat(detail)
            .containsEntry("id", versionId)
            .containsEntry("reason", "manual_review")
            .containsEntry("path", versionPath);
        assertThat((Map<String, Object>) detail.get("graph")).containsEntry("graph_id", "graph_default");
        assertThat((Map<String, Object>) detail.get("statistics")).containsEntry("totalNodes", 3);

        assertThat(graphArtifactService.listGraphVersions(PROJECT_ID, "default"))
            .hasSize(1)
            .first()
            .satisfies(item -> assertThat(item)
                .containsEntry("id", versionId)
                .containsEntry("nodeCount", 3)
                .containsEntry("edgeCount", 2)
                .containsEntry("path", versionPath));

        AtomicReference<Map<String, Object>> taskParameters = new AtomicReference<>();
        Task task = new Task();
        task.setId("task_graph_rebuild");
        task.setProjectId(PROJECT_ID);
        task.setTaskType("graph_build");
        task.setAgentName("graph_build");
        when(taskExecutorService.createTask(
            eq(PROJECT_ID),
            eq("graph_build"),
            eq("graph_build"),
            eq(Map.of()),
            any()
        )).thenAnswer(invocation -> {
            taskParameters.set(invocation.getArgument(4));
            return task;
        });
        when(taskExecutorService.executeTaskAsync("task_graph_rebuild"))
            .thenReturn(CompletableFuture.completedFuture(task));

        Task rebuildTask = graphArtifactService.rebuildGraph(PROJECT_ID, "default", Map.of(
            "actor", "tester",
            "note", "trigger rebuild"
        ));

        assertThat(rebuildTask.getId()).isEqualTo("task_graph_rebuild");
        assertThat(taskParameters.get())
            .containsEntry("project_id", PROJECT_ID)
            .containsEntry("book_id", "default")
            .containsKey("previous_graph_version_id")
            .containsKey("previous_graph_version_path");
        assertThat(String.valueOf(taskParameters.get().get("previous_graph_version_path")))
            .startsWith("graph/versions/default/");
        assertThat(graphArtifactService.listGraphVersions(PROJECT_ID, "default")).hasSize(2);
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
