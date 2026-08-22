package com.arcticblu.subscriptioncapacity.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Tunable limits for the optimization algorithm.
 *
 * @param maxTableCells ceiling on dynamic programming table size, guarding against
 *                      requests whose capacity would exhaust the heap
 */
@Validated
@ConfigurationProperties(prefix = "arcticblu.optimization")
public record OptimizationProperties(@Positive int maxTableCells) {
}