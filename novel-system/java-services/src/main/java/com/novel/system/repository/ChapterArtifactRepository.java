package com.novel.system.repository;

import com.novel.system.entity.ChapterArtifact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChapterArtifactRepository extends JpaRepository<ChapterArtifact, String> {

    List<ChapterArtifact> findByProjectIdAndBookIdOrderByVolumeNumberAscChapterNumberAscStageAsc(
        String projectId,
        String bookId
    );

    Optional<ChapterArtifact> findByProjectIdAndBookIdAndVolumeNumberAndChapterNumberAndStage(
        String projectId,
        String bookId,
        Integer volumeNumber,
        Integer chapterNumber,
        String stage
    );

    List<ChapterArtifact> findByProjectIdAndBookIdAndVolumeNumberAndChapterNumberOrderByStageAsc(
        String projectId,
        String bookId,
        Integer volumeNumber,
        Integer chapterNumber
    );

    long countByProjectId(String projectId);

    long countByStage(String stage);

    long countByProjectIdAndStage(String projectId, String stage);

    void deleteByProjectIdAndBookId(String projectId, String bookId);
}
