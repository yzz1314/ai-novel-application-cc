package com.novel.system.repository;

import com.novel.system.entity.AnalysisResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AnalysisResultRepository extends JpaRepository<AnalysisResult, String> {

    List<AnalysisResult> findBySampleIdOrderByChunkIndexAsc(String sampleId);

    Optional<AnalysisResult> findBySampleIdAndChunkId(String sampleId, String chunkId);

    long countBySampleId(String sampleId);

    long countBySampleIdAndStatus(String sampleId, String status);

    void deleteBySampleId(String sampleId);
}
