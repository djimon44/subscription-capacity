package com.arcticblu.subscriptioncapacity.service;

/** Thrown when subscription input is structurally valid but cannot be processed. */
public class InvalidSubscriptionInputException extends RuntimeException {

    public InvalidSubscriptionInputException(String message) {
        super(message);
    }
}