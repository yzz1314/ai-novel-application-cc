package com.novel.system.service;

import com.novel.system.entity.Sample;
import com.novel.system.entity.Sample.SampleStatus;
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
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SampleService {

    private final SampleRepository sampleRepository;

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

            // 3. 检查是否已存在
            if (sampleRepository.existsByFileHash(fileHash)) {
                throw new IllegalArgumentException("该文件已存在");
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
}
