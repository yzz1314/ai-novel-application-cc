package com.novel.system.repository;

import com.novel.system.entity.SampleChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface SampleChunkRepository extends JpaRepository<SampleChunk, String> {

    List<SampleChunk> findBySampleIdOrderByChunkIndexAsc(String sampleId);

    long countBySampleId(String sampleId);

    long countBySampleIdAndProcessedTrue(String sampleId);

    void deleteBySampleId(String sampleId);
}
