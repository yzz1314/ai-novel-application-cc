package com.novel.system.repository;

import com.novel.system.entity.ProjectMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectMemberRepository extends JpaRepository<ProjectMember, String> {

    Optional<ProjectMember> findByProjectIdAndUserIdAndStatus(String projectId, String userId, String status);

    List<ProjectMember> findByProjectIdAndStatusOrderByCreatedAtAsc(String projectId, String status);

    boolean existsByProjectIdAndUserIdAndStatus(String projectId, String userId, String status);
}
