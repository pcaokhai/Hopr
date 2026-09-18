/*
 * Copyright 2025 the original author.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pcaokhai.urlshortenerservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pcaokhai.urlshortenerservice.urlshort.exception.message.InvalidRequestMessage;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Rejects {@code /shorten} requests that do not carry a known {@code X-API-Key} header.
 *
 * <p>The write path is the abusable one -- every accepted request costs a keygen round trip and a
 * durable row -- so it is gated. The resolver's redirect path is deliberately not: a shortened link
 * is only useful if anyone holding it can follow it, and it is served by a different service that
 * this filter never sees.
 *
 * <p>Registered against the {@code /shorten} URL pattern only (see {@link ApiKeySecurityConfig}),
 * so actuator health, info, and Prometheus scraping stay reachable to the platform.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final List<byte[]> acceptedHashes;
    private final ObjectMapper objectMapper;

    public ApiKeyFilter(List<String> acceptedHashes, ObjectMapper objectMapper) {
        if (acceptedHashes.isEmpty()) {
            // Fail fast at startup rather than silently 401-ing every caller of a live deployment.
            throw new IllegalStateException(
                    "shortener.api-key.hashes is empty: /shorten would reject every request. "
                            + "Configure at least one SHA-256 hash (see .env.example).");
        }
        // Decoded once at startup, so a malformed hash fails the deployment instead of every request.
        this.acceptedHashes = acceptedHashes.stream().map(ApiKeyFilter::decodeHex).toList();
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // The browser preflight carries no custom headers by definition; letting it through is what
        // allows the real request that follows to arrive with the key at all.
        if (isPreflight(request)) {
            chain.doFilter(request, response);
            return;
        }

        String presented = request.getHeader(HEADER);
        if (presented == null || !isAccepted(presented)) {
            // One message for both "missing" and "wrong": telling a caller which of the two they hit
            // only helps someone probing for valid keys.
            writeUnauthorized(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isPreflight(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    private boolean isAccepted(String presented) {
        byte[] digest = sha256(presented);
        // Compare digest-to-digest with a constant-time equals: a byte-by-byte String.equals leaks,
        // through response timing, how many leading characters of a guess were right.
        return acceptedHashes.stream().anyMatch(accepted -> MessageDigest.isEqual(digest, accepted));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    private static byte[] decodeHex(String hash) {
        try {
            return HexFormat.of().parseHex(hash.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("shortener.api-key.hashes must be hex SHA-256 digests", e);
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, HEADER);
        objectMapper.writeValue(response.getOutputStream(), new InvalidRequestMessage(
                HttpStatus.UNAUTHORIZED.value(), "Missing or invalid " + HEADER + " header"));
    }
}
