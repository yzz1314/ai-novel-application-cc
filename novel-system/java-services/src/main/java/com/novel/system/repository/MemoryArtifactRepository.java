package com.novel.system.repository;

import com.novel.system.entity.MemoryArtifact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MemoryArtifactRepository extends JpaRepository<MemoryArtifact, String> {

    Optional<MemoryArtifact> findByProjectId(String projectId);

    List<MemoryArtifact> findByProjectIdOrderByUpdatedAtDesc(String projectId);

    long countByProjectId(String projectId);

    void deleteByProjectId(String projectId);
}
