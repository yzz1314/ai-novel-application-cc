package com.novel.system.repository;

import com.novel.system.entity.AccessOrganizationMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AccessOrganizationMemberRepository extends JpaRepository<AccessOrganizationMember, String> {

    List<AccessOrganizationMember> findByOrganizationIdAndStatusOrderByCreatedAtAsc(String organizationId, String status);

    List<AccessOrganizationMember> findByUserIdAndStatusOrderByUpdatedAtDesc(String userId, String status);

    Optional<AccessOrganizationMember> findByOrganizationIdAndUserIdAndStatus(String organizationId, String userId, String status);
}
