package com.tokenizedtradingplatform.demo.exception;

public class LockAcquisitionException extends RuntimeException {
    public LockAcquisitionException(String key) {
        super("Could not acquire distributed lock: " + key);
    }
}
