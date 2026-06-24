package com.novel.system.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    
    @Bean
    public OpenAPI novelSystemOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Novel System API")
                .description("小说样本拆解与长篇创作系统 API")
                .version("v1.0.0")
                .contact(new Contact()
                    .name("Novel System Team")));
    }
}
