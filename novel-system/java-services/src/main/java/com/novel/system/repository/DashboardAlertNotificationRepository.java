package com.novel.system.repository;

import com.novel.system.entity.DashboardAlertNotification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DashboardAlertNotificationRepository extends JpaRepository<DashboardAlertNotification, String> {

    Optional<DashboardAlertNotification> findByAlertIdAndConditionKeyAndEscalationLevel(
        String alertId,
        String conditionKey,
        String escalationLevel
    );

    List<DashboardAlertNotification> findAllByOrderByLastSeenAtDesc(Pageable pageable);
}
