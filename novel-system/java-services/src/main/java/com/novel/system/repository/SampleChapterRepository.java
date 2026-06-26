package com.novel.system.repository;

import com.novel.system.entity.SampleChapter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface SampleChapterRepository extends JpaRepository<SampleChapter, String> {

    List<SampleChapter> findBySampleIdOrderByChapterIndexAsc(String sampleId);

    long countBySampleId(String sampleId);

    void deleteBySampleId(String sampleId);
}
