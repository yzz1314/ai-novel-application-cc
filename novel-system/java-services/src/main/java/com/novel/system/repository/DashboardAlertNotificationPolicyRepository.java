package com.novel.system.repository;

import com.novel.system.entity.DashboardAlertNotificationPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DashboardAlertNotificationPolicyRepository extends JpaRepository<DashboardAlertNotificationPolicy, String> {
}
