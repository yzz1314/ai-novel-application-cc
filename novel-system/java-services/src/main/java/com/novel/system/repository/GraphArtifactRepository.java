package com.novel.system.repository;

import com.novel.system.entity.GraphArtifact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GraphArtifactRepository extends JpaRepository<GraphArtifact, String> {

    Optional<GraphArtifact> findByProjectIdAndBookId(String projectId, String bookId);

    List<GraphArtifact> findByProjectIdOrderByUpdatedAtDesc(String projectId);

    long countByProjectId(String projectId);

    void deleteByProjectId(String projectId);
}
