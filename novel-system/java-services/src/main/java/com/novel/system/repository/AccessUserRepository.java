package com.novel.system.repository;

import com.novel.system.entity.AccessUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccessUserRepository extends JpaRepository<AccessUser, String> {

    List<AccessUser> findByOrganizationIdAndStatusOrderByUpdatedAtDesc(String organizationId, String status);
}
