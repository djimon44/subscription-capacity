package com.arcticblu.subscriptioncapacity.service;

import java.util.UUID;

/** Thrown when no optimization run exists for a given identifier. */
public class OptimizationRunNotFoundException extends RuntimeException {

    public OptimizationRunNotFoundException(UUID requestId) {
        super("No optimization run found with id " + requestId);
    }
}