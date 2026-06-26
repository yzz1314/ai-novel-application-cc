package com.novel.system.repository;

import com.novel.system.entity.Task;
import com.novel.system.entity.Task.TaskStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TaskRepository extends JpaRepository<Task, String> {

    List<Task> findByProjectId(String projectId);

    List<Task> findByProjectIdAndStatus(String projectId, TaskStatus status);

    List<Task> findByStatus(TaskStatus status);

    List<Task> findByStatusOrderByCreatedAtAsc(TaskStatus status);

    List<Task> findTop10ByProjectIdOrderByCreatedAtDesc(String projectId);

    List<Task> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<Task> findByProjectIdOrderByCreatedAtDesc(String projectId, Pageable pageable);

    List<Task> findByStatusOrderByCreatedAtDesc(TaskStatus status, Pageable pageable);

    List<Task> findByProjectIdAndStatusOrderByCreatedAtDesc(String projectId, TaskStatus status, Pageable pageable);
}
