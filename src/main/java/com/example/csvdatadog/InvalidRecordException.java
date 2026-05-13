package com.example.csvdatadog;

public class InvalidRecordException extends RuntimeException {
    public InvalidRecordException(String message) {
        super(message);
    }
}
