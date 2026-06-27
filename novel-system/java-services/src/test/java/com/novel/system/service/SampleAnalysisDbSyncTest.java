package com.novel.system.service;

import com.novel.system.entity.AnalysisResult;
import com.novel.system.entity.Sample;
import com.novel.system.entity.SampleChunk;
import com.novel.system.repository.AnalysisResultRepository;
import com.novel.system.repository.SampleChapterRepository;
import com.novel.system.repository.SampleChunkRepository;
import com.novel.system.repository.SampleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SampleAnalysisDbSyncTest {

    private static final String PROJECT_ID = "project_coverage_db";
    private static final String SAMPLE_ID = "sample_coverage_db";

    @TempDir
    Path tempDir;

    @Mock
    private SampleRepository sampleRepository;
    @Mock
    private SampleChapterRepository sampleChapterRepository;
    @Mock
    private SampleChunkRepository sampleChunkRepository;
    @Mock
    private AnalysisResultRepository analysisResultRepository;

    private SampleService sampleService;
    private AnalysisResultService analysisResultService;

    @BeforeEach
    void setUp() {
        sampleService = new SampleService(
            sampleRepository,
            sampleChapterRepository,
            sampleChunkRepository,
            analysisResultRepository
        );
        ReflectionTestUtils.setField(sampleService, "basePath", tempDir.toString());

        analysisResultService = new AnalysisResultService(sampleService, analysisResultRepository);
        ReflectionTestUtils.setField(analysisResultService, "basePath", tempDir.toString());

        when(sampleRepository.findById(SAMPLE_ID)).thenReturn(Optional.of(sample()));
    }

    @Test
    void sampleStructureSyncMarksFailedAnalysisChunksUnprocessed() throws Exception {
        writeWorkspaceFixture();
        ArgumentCaptor<List<SampleChunk>> captor = ArgumentCaptor.forClass(List.class);
        when(sampleChunkRepository.saveAll(captor.capture())).thenReturn(List.of());

        sampleService.syncSampleStructureFromWorkspace(PROJECT_ID, SAMPLE_ID);

        List<SampleChunk> chunks = captor.getValue();
        assertThat(chunks).hasSize(2);
        assertThat(chunks).extracting(SampleChunk::getChunkId).containsExactly("chunk_1", "chunk_2");
        assertThat(chunks.get(0).getProcessed()).isTrue();
        assertThat(chunks.get(1).getProcessed()).isFalse();
        assertThat(chunks.get(1).getAnalysisPath())
            .isEqualTo("analysis/per_chunk/sample_coverage_db/chunk_2_analysis.json");
    }

    @Test
    void analysisResultSyncPersistsFailedAnalysisStatus() throws Exception {
        writeWorkspaceFixture();
        ArgumentCaptor<List<AnalysisResult>> captor = ArgumentCaptor.forClass(List.class);
        when(analysisResultRepository.saveAll(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        var summary = analysisResultService.syncAnalysisResultsFromWorkspace(PROJECT_ID, SAMPLE_ID);

        List<AnalysisResult> results = captor.getValue();
        assertThat(results).hasSize(2);
        assertThat(results).extracting(AnalysisResult::getChunkId).containsExactly("chunk_1", "chunk_2");
        assertThat(results.get(0).getStatus()).isEqualTo("SUCCESS");
        assertThat(results.get(1).getStatus()).isEqualTo("FAILED");
        assertThat(results.get(1).getSummary()).contains("LLM timeout");
        assertThat(summary)
            .containsEntry("dbAnalysisResultCount", 2)
            .containsEntry("dbSuccessfulAnalysisResultCount", 1L);
    }

    private void writeWorkspaceFixture() throws Exception {
        writeProjectFile("samples/manifests/" + SAMPLE_ID + "_manifest.json", """
            {
              "title": "Coverage DB Sample",
              "total_chars": 20,
              "chapters": [
                {"chapter_index": 1, "title": "第一章", "start_offset": 0, "end_offset": 20, "char_count": 20}
              ]
            }
            """);
        writeProjectFile("samples/chunks/" + SAMPLE_ID + "/chunk_1.json", """
            {
              "id": "chunk_1",
              "chapter_index": 1,
              "part_index": 1,
              "start_offset": 0,
              "end_offset": 10,
              "char_count": 10
            }
            """);
        writeProjectFile("samples/chunks/" + SAMPLE_ID + "/chunk_2.json", """
            {
              "id": "chunk_2",
              "chapter_index": 1,
              "part_index": 2,
              "start_offset": 10,
              "end_offset": 20,
              "char_count": 10
            }
            """);
        writeProjectFile("analysis/per_chunk/" + SAMPLE_ID + "/chunk_1_analysis.json", """
            {
              "chunk_id": "chunk_1",
              "chunk_index": 1,
              "analysis": {
                "summary": "已完成分析",
                "characters": ["主角"]
              }
            }
            """);
        writeProjectFile("analysis/per_chunk/" + SAMPLE_ID + "/chunk_2_analysis.json", """
            {
              "chunk_id": "chunk_2",
              "chunk_index": 2,
              "error": "LLM timeout"
            }
            """);
    }

    private void writeProjectFile(String relativePath, String content) throws Exception {
        Path file = tempDir.resolve("projects").resolve(PROJECT_ID).resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private Sample sample() {
        Sample sample = new Sample();
        sample.setId(SAMPLE_ID);
        sample.setProjectId(PROJECT_ID);
        sample.setFileName("sample.md");
        sample.setFilePath("sample.md");
        sample.setFileHash("hash");
        sample.setFileSizeBytes(100L);
        sample.setStatus(Sample.SampleStatus.CHUNKED);
        return sample;
    }
}
