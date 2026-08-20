package com.arcticblu.subscriptioncapacity.algorithm;

public class CapacityTooLargeException extends RuntimeException {
    public CapacityTooLargeException(String message) {
        super(message);
    }
}
