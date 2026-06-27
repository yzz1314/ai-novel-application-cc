package com.novel.system.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.system.entity.Sample;
import com.novel.system.entity.SampleChapter;
import com.novel.system.entity.SampleChunk;
import com.novel.system.entity.Sample.SampleStatus;
import com.novel.system.repository.AnalysisResultRepository;
import com.novel.system.repository.SampleChapterRepository;
import com.novel.system.repository.SampleChunkRepository;
import com.novel.system.repository.SampleRepository;
import com.novel.system.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SampleService {

    private final SampleRepository sampleRepository;
    private final SampleChapterRepository sampleChapterRepository;
    private final SampleChunkRepository sampleChunkRepository;
    private final AnalysisResultRepository analysisResultRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${file.storage.base-path:/workspace}")
    private String basePath;

    private static final long MAX_FILE_SIZE = 20 * 1024 * 1024; // 20MB

    @Transactional
    public Sample uploadSample(String projectId, MultipartFile file) {
        log.info("Uploading sample for project: {}", projectId);

        try {
            // 1. 验证文件
            validateFile(file);

            // 2. 计算文件哈希
            byte[] fileBytes = file.getBytes();
            String fileHash = calculateFileHash(fileBytes);

            // 3. 检查同项目是否已存在
            if (sampleRepository.existsByProjectIdAndFileHash(projectId, fileHash)) {
                throw new IllegalArgumentException("该项目下已存在相同内容的文件");
            }

            // 4. 保存原始文件
            String fileName = file.getOriginalFilename();
            Path rawFilePath = Paths.get(basePath, "projects", projectId, "samples", "raw", fileName);
            Files.createDirectories(rawFilePath.getParent());
            Files.write(rawFilePath, fileBytes);

            // 5. 创建Sample记录
            Sample sample = new Sample();
            sample.setProjectId(projectId);
            sample.setFileName(fileName);
            sample.setFilePath(rawFilePath.toString());
            sample.setFileHash(fileHash);
            sample.setFileSizeBytes(file.getSize());
            sample.setStatus(SampleStatus.UPLOADED);

            sample = sampleRepository.save(sample);

            log.info("Sample uploaded with ID: {}", sample.getId());
            return sample;

        } catch (IOException e) {
            log.error("Failed to upload sample", e);
            throw new RuntimeException("文件上传失败", e);
        }
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }

        String fileName = file.getOriginalFilename();
        if (fileName == null || (!fileName.endsWith(".md") && !fileName.endsWith(".txt"))) {
            throw new IllegalArgumentException("仅支持 .md 和 .txt 格式");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("文件大小超过20MB限制");
        }
    }

    private String calculateFileHash(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("哈希计算失败", e);
        }
    }

    public Sample getSample(String sampleId) {
        return sampleRepository.findById(sampleId)
            .orElseThrow(() -> new ResourceNotFoundException("样本不存在: " + sampleId));
    }

    public List<Sample> listSamplesByProject(String projectId) {
        return sampleRepository.findByProjectId(projectId);
    }

    public String readSampleContent(String sampleId) {
        Sample sample = getSample(sampleId);
        Path filePath = Paths.get(sample.getFilePath());

        try {
            return Files.readString(filePath);
        } catch (IOException e) {
            log.error("Failed to read sample content", e);
            throw new RuntimeException("读取文件内容失败", e);
        }
    }

    @Transactional
    public Sample updateSampleStatus(String sampleId, SampleStatus newStatus) {
        Sample sample = getSample(sampleId);
        sample.setStatus(newStatus);
        return sampleRepository.save(sample);
    }

    @Transactional
    public Sample updateSampleMetadata(String sampleId, String title, Integer totalChars, Integer totalChapters) {
        Sample sample = getSample(sampleId);
        if (title != null) {
            sample.setTitle(title);
        }
        if (totalChars != null) {
            sample.setTotalChars(totalChars);
        }
        if (totalChapters != null) {
            sample.setTotalChapters(totalChapters);
        }
        return sampleRepository.save(sample);
    }

    @Transactional
    public void syncSampleStructureFromWorkspace(String projectId, String sampleId) {
        Sample sample = getSample(sampleId);
        if (!projectId.equals(sample.getProjectId())) {
            throw new ResourceNotFoundException("样本不属于当前项目: " + sampleId);
        }

        Path manifestPath = Paths.get(basePath, "projects", projectId, "samples", "manifests", sampleId + "_manifest.json");
        if (!Files.exists(manifestPath)) {
            log.warn("Sample manifest not found, skip DB sync: {}", manifestPath);
            return;
        }

        try {
            Map<String, Object> manifest = objectMapper.readValue(manifestPath.toFile(), new TypeReference<>() {});
            syncChapters(projectId, sampleId, manifest);
            syncChunks(projectId, sampleId);
            log.info(
                "Synced sample structure to DB: sample={}, chapters={}, chunks={}",
                sampleId,
                sampleChapterRepository.countBySampleId(sampleId),
                sampleChunkRepository.countBySampleId(sampleId)
            );
        } catch (IOException e) {
            throw new RuntimeException("同步样本章节/分块到数据库失败: " + sampleId, e);
        }
    }

    public List<SampleChapter> listSampleChapters(String sampleId) {
        getSample(sampleId);
        return sampleChapterRepository.findBySampleIdOrderByChapterIndexAsc(sampleId);
    }

    public List<SampleChunk> listSampleChunks(String sampleId) {
        getSample(sampleId);
        return sampleChunkRepository.findBySampleIdOrderByChunkIndexAsc(sampleId);
    }

    public Map<String, Object> getSampleStructureStats(String sampleId) {
        Sample sample = getSample(sampleId);
        return Map.of(
            "sampleId", sampleId,
            "dbChapterCount", sampleChapterRepository.countBySampleId(sampleId),
            "dbChunkCount", sampleChunkRepository.countBySampleId(sampleId),
            "dbProcessedChunkCount", sampleChunkRepository.countBySampleIdAndProcessedTrue(sampleId),
            "sampleTotalChapters", sample.getTotalChapters() != null ? sample.getTotalChapters() : 0
        );
    }

    private void syncChapters(String projectId, String sampleId, Map<String, Object> manifest) {
        sampleChapterRepository.deleteBySampleId(sampleId);
        Object chaptersValue = manifest.get("chapters");
        if (!(chaptersValue instanceof List<?> chapters)) {
            return;
        }

        List<SampleChapter> entities = chapters.stream()
            .filter(Map.class::isInstance)
            .map(Map.class::cast)
            .map(chapter -> {
                Integer chapterIndex = toInteger(chapter.get("chapter_index"));
                SampleChapter entity = new SampleChapter();
                entity.setId(sampleId + "_chapter_" + (chapterIndex != null ? chapterIndex : 0));
                entity.setProjectId(projectId);
                entity.setSampleId(sampleId);
                entity.setChapterIndex(chapterIndex);
                entity.setTitle(stringValue(chapter.get("title")));
                entity.setStartOffset(toInteger(chapter.get("start_offset")));
                entity.setEndOffset(toInteger(chapter.get("end_offset")));
                entity.setCharCount(toInteger(chapter.get("char_count")));
                return entity;
            })
            .toList();
        sampleChapterRepository.saveAll(entities);
    }

    private void syncChunks(String projectId, String sampleId) throws IOException {
        sampleChunkRepository.deleteBySampleId(sampleId);
        Path chunksDir = Paths.get(basePath, "projects", projectId, "samples", "chunks", sampleId);
        if (!Files.exists(chunksDir)) {
            return;
        }

        List<Path> chunkFiles;
        try (var stream = Files.list(chunksDir)) {
            chunkFiles = stream
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                .sorted()
                .toList();
        }

        List<SampleChunk> entities = chunkFiles.stream()
            .map(path -> readChunk(projectId, sampleId, chunksDir, path))
            .sorted(Comparator.comparing(SampleChunk::getChunkIndex, Comparator.nullsLast(Integer::compareTo)))
            .toList();
        sampleChunkRepository.saveAll(entities);
    }

    private SampleChunk readChunk(String projectId, String sampleId, Path chunksDir, Path path) {
        try {
            Map<String, Object> chunk = objectMapper.readValue(path.toFile(), new TypeReference<>() {});
            String chunkId = stringValue(chunk.get("id"));
            SampleChunk entity = new SampleChunk();
            entity.setId(sampleId + "_" + chunkId);
            entity.setProjectId(projectId);
            entity.setSampleId(sampleId);
            entity.setChunkId(chunkId);
            entity.setChunkIndex(parseChunkIndex(chunkId));
            entity.setChapterIndex(toInteger(chunk.get("chapter_index")));
            entity.setPartIndex(toInteger(chunk.get("part_index")));
            entity.setStartOffset(toInteger(chunk.get("start_offset")));
            entity.setEndOffset(toInteger(chunk.get("end_offset")));
            entity.setCharCount(toInteger(chunk.get("char_count")));
            entity.setHeadingPath(stringValue(chunk.get("heading_path")));
            entity.setFilePath(chunksDir.relativize(path).toString().replace("\\", "/"));
            Path analysisPath = Paths.get(basePath, "projects", projectId, "analysis", "per_chunk", sampleId, chunkId + "_analysis.json");
            boolean hasAnalysis = Files.exists(analysisPath);
            entity.setProcessed(hasAnalysis && isSuccessfulAnalysis(analysisPath));
            entity.setAnalysisPath(hasAnalysis
                ? Paths.get(basePath, "projects", projectId).relativize(analysisPath).toString().replace("\\", "/")
                : null);
            return entity;
        } catch (IOException e) {
            throw new RuntimeException("读取样本分块失败: " + path.getFileName(), e);
        }
    }

    private boolean isSuccessfulAnalysis(Path analysisPath) {
        try {
            Map<String, Object> analysis = objectMapper.readValue(analysisPath.toFile(), new TypeReference<>() {});
            Object error = analysis.get("error");
            if (error != null && !error.toString().isBlank()) {
                return false;
            }
            Object status = analysis.get("status");
            return status == null || !List.of("failed", "failure", "error").contains(status.toString().toLowerCase());
        } catch (IOException e) {
            return false;
        }
    }

    private Integer parseChunkIndex(String chunkId) {
        if (chunkId == null) {
            return null;
        }
        int pos = chunkId.lastIndexOf('_');
        if (pos < 0 || pos == chunkId.length() - 1) {
            return null;
        }
        return toInteger(chunkId.substring(pos + 1));
    }

    private Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    @Transactional
    public void deleteSample(String sampleId) {
        Sample sample = getSample(sampleId);
        analysisResultRepository.deleteBySampleId(sampleId);
        sampleChapterRepository.deleteBySampleId(sampleId);
        sampleChunkRepository.deleteBySampleId(sampleId);
        sampleRepository.delete(sample);
    }
}
