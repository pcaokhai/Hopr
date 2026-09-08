package com.pcaokhai.resolverservice.exception;

public class KeyNotFoundException extends RuntimeException {
    public KeyNotFoundException() {super("Key Not Found");}
    public KeyNotFoundException(String message) {
        super(message);
    }
}
