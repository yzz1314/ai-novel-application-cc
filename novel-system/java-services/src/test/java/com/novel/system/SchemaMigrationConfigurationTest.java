package com.novel.system;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaMigrationConfigurationTest {

    @Test
    void defaultProfileUsesFlywayAndHibernateValidation() {
        Properties properties = loadYaml("application.yml");

        assertThat(properties.getProperty("spring.flyway.enabled")).isEqualTo("${SPRING_FLYWAY_ENABLED:true}");
        assertThat(properties.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration");
        assertThat(properties.getProperty("spring.jpa.hibernate.ddl-auto"))
            .isEqualTo("${SPRING_JPA_HIBERNATE_DDL_AUTO:validate}");
    }

    @Test
    void prodAndCiProfilesValidateMigratedSchema() {
        assertThat(loadYaml("application-prod.yml").getProperty("spring.jpa.hibernate.ddl-auto"))
            .isEqualTo("validate");
        assertThat(loadYaml("application-ci.yml").getProperty("spring.jpa.hibernate.ddl-auto"))
            .isEqualTo("validate");
    }

    private Properties loadYaml(String path) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(path));
        Properties properties = factory.getObject();
        assertThat(properties).as(path).isNotNull();
        return properties;
    }
}
