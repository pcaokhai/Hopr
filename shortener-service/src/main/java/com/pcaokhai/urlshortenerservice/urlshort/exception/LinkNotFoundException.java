package com.pcaokhai.urlshortenerservice.urlshort.exception;

public class LinkNotFoundException extends RuntimeException {
    public LinkNotFoundException(String shortKey) {
        super("Link " + shortKey + " not found");
    }
}
