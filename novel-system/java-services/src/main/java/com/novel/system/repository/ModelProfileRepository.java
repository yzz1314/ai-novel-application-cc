package com.novel.system.repository;

import com.novel.system.entity.ModelProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ModelProfileRepository extends JpaRepository<ModelProfile, String> {

    List<ModelProfile> findAllByOrderByCreatedAtAsc();

    Optional<ModelProfile> findByDefaultProfileTrue();
}
