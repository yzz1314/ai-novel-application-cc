package com.novel.system.repository;

import com.novel.system.entity.OutlineArtifact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OutlineArtifactRepository extends JpaRepository<OutlineArtifact, String> {

    List<OutlineArtifact> findByProjectIdOrderByUpdatedAtDesc(String projectId);

    Optional<OutlineArtifact> findByProjectIdAndBookId(String projectId, String bookId);

    long countByProjectId(String projectId);
}
