package com.novel.system.repository;

import com.novel.system.entity.DashboardMetricSnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DashboardMetricSnapshotRepository extends JpaRepository<DashboardMetricSnapshot, String> {

    List<DashboardMetricSnapshot> findAllByOrderByCapturedAtDesc(Pageable pageable);
}
