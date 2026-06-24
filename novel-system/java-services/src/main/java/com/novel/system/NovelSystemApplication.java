package com.novel.system;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class NovelSystemApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(NovelSystemApplication.class, args);
    }
}
