package com.novel.system.repository;

import com.novel.system.entity.RetrievalArtifact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RetrievalArtifactRepository extends JpaRepository<RetrievalArtifact, String> {

    Optional<RetrievalArtifact> findByProjectId(String projectId);

    List<RetrievalArtifact> findByProjectIdOrderByUpdatedAtDesc(String projectId);

    long countByProjectId(String projectId);

    void deleteByProjectId(String projectId);
}
