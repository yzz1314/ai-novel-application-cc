package com.novel.system.repository;

import com.novel.system.entity.AccessAuditEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccessAuditEventRepository extends JpaRepository<AccessAuditEvent, String> {

    List<AccessAuditEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<AccessAuditEvent> findByOrganizationIdOrderByCreatedAtDesc(String organizationId, Pageable pageable);

    List<AccessAuditEvent> findByProjectIdOrderByCreatedAtDesc(String projectId, Pageable pageable);

    List<AccessAuditEvent> findByActorIdOrderByCreatedAtDesc(String actorId, Pageable pageable);
}
