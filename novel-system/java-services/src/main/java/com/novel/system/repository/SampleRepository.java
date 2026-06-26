package com.novel.system.repository;

import com.novel.system.entity.Sample;
import com.novel.system.entity.Sample.SampleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface SampleRepository extends JpaRepository<Sample, String> {

    List<Sample> findByProjectId(String projectId);

    List<Sample> findByProjectIdAndStatus(String projectId, SampleStatus status);

    List<Sample> findByStatus(SampleStatus status);

    long countByProjectId(String projectId);

    long countByStatus(SampleStatus status);

    long countByProjectIdAndStatus(String projectId, SampleStatus status);

    boolean existsByFileHash(String fileHash);

    boolean existsByProjectIdAndFileHash(String projectId, String fileHash);
}
