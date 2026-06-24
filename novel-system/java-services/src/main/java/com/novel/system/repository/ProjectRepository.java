package com.novel.system.repository;

import com.novel.system.entity.Project;
import com.novel.system.entity.Project.ProjectStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ProjectRepository extends JpaRepository<Project, String> {

    List<Project> findByStatus(ProjectStatus status);

    List<Project> findByStatusIn(List<ProjectStatus> statuses);

    List<Project> findByNameContainingIgnoreCase(String keyword);

    boolean existsByName(String name);
}
