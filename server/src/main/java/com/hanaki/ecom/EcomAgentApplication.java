package com.hanaki.ecom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 应用入口。JDK 21 + Spring Boot 3；异步任务用于记忆候选和非关键遥测处理。 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class EcomAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(EcomAgentApplication.class, args);
    }
}
