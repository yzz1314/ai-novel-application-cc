package com.novel.system.repository;

import com.novel.system.entity.AccessOrganization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AccessOrganizationRepository extends JpaRepository<AccessOrganization, String> {
}
