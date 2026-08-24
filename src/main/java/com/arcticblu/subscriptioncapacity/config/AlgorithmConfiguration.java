package com.arcticblu.subscriptioncapacity.config;

import com.arcticblu.subscriptioncapacity.algorithm.AdaptiveKnapsackSolver;
import com.arcticblu.subscriptioncapacity.algorithm.KnapsackSolver;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(OptimizationProperties.class)
public class AlgorithmConfiguration {

    @Bean
    public KnapsackSolver knapsackSolver(OptimizationProperties properties) {
        return new AdaptiveKnapsackSolver(properties.maxTableCells());
    }

    @Bean
    public Clock clock() {
        // Truncated to milliseconds so the timestamp returned by a create matches the
        // value read back from PostgreSQL, whose TIMESTAMPTZ resolution is coarser than
        // the JVM clock's.
        return Clock.tick(Clock.systemUTC(), Duration.ofMillis(1));
    }
}