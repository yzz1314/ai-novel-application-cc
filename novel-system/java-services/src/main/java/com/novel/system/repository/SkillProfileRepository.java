package com.novel.system.repository;

import com.novel.system.entity.SkillProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SkillProfileRepository extends JpaRepository<SkillProfile, String> {

    List<SkillProfile> findByProjectIdOrderByUpdatedAtDesc(String projectId);

    Optional<SkillProfile> findByProjectIdAndName(String projectId, String name);
}
