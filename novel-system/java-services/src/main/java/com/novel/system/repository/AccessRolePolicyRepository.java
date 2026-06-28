package com.novel.system.repository;

import com.novel.system.entity.AccessRolePolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AccessRolePolicyRepository extends JpaRepository<AccessRolePolicy, String> {

    List<AccessRolePolicy> findByStatusOrderByActionKeyAsc(String status);

    Optional<AccessRolePolicy> findByActionKeyAndStatus(String actionKey, String status);
}
