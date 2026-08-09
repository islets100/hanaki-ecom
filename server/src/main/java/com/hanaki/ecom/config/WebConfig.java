package com.hanaki.ecom.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class WebConfig {
    private final List<String> allowedOrigins;

    public WebConfig(@Value("${agent.security.allowed-origins:http://localhost:3000,http://127.0.0.1:3000,http://localhost:3001,http://127.0.0.1:3001}") String allowedOrigins) {
        this.allowedOrigins = java.util.Arrays.stream(allowedOrigins.split(","))
                .map(String::strip).filter(value -> !value.isBlank()).distinct().toList();
    }

    /** 本地前端使用 3000 端口；生产环境应把 allowedOrigins 改为实际域名。 */
    @Bean
    CorsFilter corsFilter() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "Authorization", "Idempotency-Key"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return new CorsFilter(source);
    }

    /** Best-of-3 使用独立有界线程池，避免占用公共 ForkJoinPool。 */
    @Bean(destroyMethod = "shutdown")
    ExecutorService candidateExecutor(
            @Value("${agent.evaluation.concurrency.executor-core-size:16}") int coreSize,
            @Value("${agent.evaluation.concurrency.executor-max-size:32}") int maxSize,
            @Value("${agent.evaluation.concurrency.executor-queue-capacity:100}") int queueCapacity) {
        // fixedThreadPool 实际使用无界队列；这里显式限制积压，过载时由上层安全降级。
        int core = Math.max(3, coreSize);
        int max = Math.max(core, maxSize);
        return new ThreadPoolExecutor(core, max, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(3, queueCapacity)),
                Thread.ofPlatform().name("candidate-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** 模型调用独立于 Graph 分支线程，超时取消不会反向占满同一个执行器造成自锁。 */
    @Bean(name = "evaluationModelExecutor", destroyMethod = "shutdown")
    ExecutorService evaluationModelExecutor(
            @Value("${agent.evaluation.concurrency.model-core-size:16}") int coreSize,
            @Value("${agent.evaluation.concurrency.model-max-size:32}") int maxSize,
            @Value("${agent.evaluation.concurrency.model-queue-capacity:100}") int queueCapacity) {
        int core = Math.max(3, coreSize);
        int max = Math.max(core, maxSize);
        return new ThreadPoolExecutor(core, max, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(3, queueCapacity)),
                Thread.ofPlatform().name("evaluation-model-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
