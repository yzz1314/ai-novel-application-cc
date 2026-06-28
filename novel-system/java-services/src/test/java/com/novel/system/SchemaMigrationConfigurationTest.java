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
        assertThat(properties.getProperty("security.access.enforce")).isEqualTo("${ACCESS_CONTROL_ENFORCE:false}");
        assertThat(properties.getProperty("security.access.require-authentication")).isEqualTo("${ACCESS_CONTROL_REQUIRE_AUTH:false}");
        assertThat(properties.getProperty("security.access.jwt.enabled")).isEqualTo("${ACCESS_JWT_ENABLED:false}");
        assertThat(properties.getProperty("security.access.jwt.require-bearer")).isEqualTo("${ACCESS_JWT_REQUIRE_BEARER:false}");
    }

    @Test
    void prodAndCiProfilesValidateMigratedSchema() {
        Properties prod = loadYaml("application-prod.yml");
        assertThat(prod.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(prod.getProperty("security.access.enforce")).isEqualTo("${ACCESS_CONTROL_ENFORCE:true}");
        assertThat(prod.getProperty("security.access.require-authentication")).isEqualTo("${ACCESS_CONTROL_REQUIRE_AUTH:true}");
        assertThat(prod.getProperty("security.access.jwt.enabled")).isEqualTo("${ACCESS_JWT_ENABLED:true}");
        assertThat(prod.getProperty("security.access.jwt.require-bearer")).isEqualTo("${ACCESS_JWT_REQUIRE_BEARER:true}");
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
