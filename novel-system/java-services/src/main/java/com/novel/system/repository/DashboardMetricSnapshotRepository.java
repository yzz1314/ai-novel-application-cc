package com.novel.system.repository;

import com.novel.system.entity.DashboardMetricSnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DashboardMetricSnapshotRepository extends JpaRepository<DashboardMetricSnapshot, String> {

    List<DashboardMetricSnapshot> findAllByOrderByCapturedAtDesc(Pageable pageable);

    Optional<DashboardMetricSnapshot> findFirstByOrderByCapturedAtDesc();

    @Transactional
    long deleteByCapturedAtBefore(LocalDateTime cutoff);
}
