package com.novel.system;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("ci")
@EnabledIfEnvironmentVariable(named = "RUN_DB_MIGRATION_TEST", matches = "true")
class MigrationSchemaValidationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayMigratesAndHibernateValidatesApplicationSchema() {
        Integer appliedMigrations = jdbcTemplate.queryForObject(
            "select count(*) from flyway_schema_history where success = true",
            Integer.class
        );
        Integer managedTables = jdbcTemplate.queryForObject(
            """
            select count(*)
            from information_schema.tables
            where table_schema = current_schema()
              and table_name in (
                'projects',
                'samples',
                'tasks',
                'access_organizations',
                'access_users',
                'access_organization_members',
                'access_audit_events',
                'access_role_policies',
                'project_members',
                'model_profiles',
                'skill_profiles',
                'sample_chapters',
                'sample_chunks',
                'analysis_results',
                'outline_artifacts',
                'chapter_artifacts',
                'memory_artifacts',
                'graph_artifacts',
                'retrieval_artifacts',
                'retrieval_vectors',
                'dashboard_alert_states',
                'dashboard_metric_snapshots',
                'dashboard_alert_notifications',
                'dashboard_alert_notification_policies'
              )
            """,
            Integer.class
        );

        assertThat(appliedMigrations).isNotNull().isGreaterThanOrEqualTo(1);
        assertThat(managedTables).isEqualTo(24);
    }
}
