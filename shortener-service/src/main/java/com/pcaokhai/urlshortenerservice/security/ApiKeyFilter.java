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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rejects {@code /shorten} requests that do not carry a known {@code X-API-Key} header.
 *
 * <p>The write path is the abusable one -- every accepted request costs a keygen round trip and a
 * durable row -- so it is gated. The resolver's redirect path is deliberately not: a shortened link
 * is only useful if anyone holding it can follow it, and it is served by a different service that
 * this filter never sees.
 *
 * <p>Registered against the versioned {@code /v1/shorten} and {@code /v1/links} URL patterns only
 * (see {@link ApiKeySecurityConfig}), so actuator health, info, and Prometheus scraping stay
 * reachable to the platform.
 *
 * <p>Beyond authenticating the caller, the filter <em>identifies</em> it: each configured key
 * carries an owner id, which is published as the {@link #OWNER_ID_ATTRIBUTE} request attribute
 * for the controllers downstream to scope reads and writes by.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    /** Request attribute carrying the authenticated caller's owner id to the controllers. */
    public static final String OWNER_ID_ATTRIBUTE = "hopr.ownerId";

    private final Map<byte[], String> ownerByHash;
    private final ObjectMapper objectMapper;

    public ApiKeyFilter(Map<String, String> ownerByHash, ObjectMapper objectMapper) {
        if (ownerByHash.isEmpty()) {
            // Fail fast at startup rather than silently 401-ing every caller of a live deployment.
            throw new IllegalStateException(
                    "shortener.api-key.owners is empty: /v1/shorten would reject every request. "
                            + "Configure at least one `<sha-256>:<owner-id>` entry (see .env.example).");
        }
        // Decoded once at startup, so a malformed hash fails the deployment instead of every request.
        Map<byte[], String> decoded = new LinkedHashMap<>();
        ownerByHash.forEach((hash, ownerId) -> decoded.put(decodeHex(hash), ownerId));
        this.ownerByHash = decoded;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        String ownerId = presented == null ? null : resolveOwner(presented);
        if (ownerId == null) {
            // One message for both "missing" and "wrong": telling a caller which of the two they hit
            // only helps someone probing for valid keys.
            writeUnauthorized(response);
            return;
        }
        request.setAttribute(OWNER_ID_ATTRIBUTE, ownerId);
        chain.doFilter(request, response);
    }

    /** The owner the presented key identifies, or null when it is not a key we issued. */
    private String resolveOwner(String presented) {
        byte[] digest = sha256(presented);
        // Compare digest-to-digest with a constant-time equals: a byte-by-byte String.equals leaks,
        // through response timing, how many leading characters of a guess were right.
        return ownerByHash.entrySet().stream()
                .filter(entry -> MessageDigest.isEqual(digest, entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
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
            throw new IllegalStateException("shortener.api-key.owners must start with a hex SHA-256 digest", e);
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new InvalidRequestMessage(
                HttpStatus.UNAUTHORIZED.value(), "Missing or invalid " + HEADER + " header"));
    }
}
