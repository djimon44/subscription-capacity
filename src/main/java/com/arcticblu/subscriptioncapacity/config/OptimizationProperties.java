package com.arcticblu.subscriptioncapacity.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Tunable limits for the optimization algorithm.
 *
 * @param maxTableCells             ceiling on dynamic programming table size, guarding
 *                                  against requests whose capacity would exhaust the heap
 * @param maxCarriedForwardCandidates ceiling on how many previously declined candidates a
 *                                  single run will pull in.
 */
@Validated
@ConfigurationProperties(prefix = "arcticblu.optimization")
public record OptimizationProperties(@Positive int maxTableCells, @Positive int maxCarriedForwardCandidates) {
}