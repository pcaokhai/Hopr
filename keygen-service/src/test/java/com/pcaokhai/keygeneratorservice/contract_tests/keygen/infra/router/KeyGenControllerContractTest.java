package com.pcaokhai.keygeneratorservice.contract_tests.keygen.infra.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pcaokhai.keygeneratorservice.keygen.application.KeyGenUseCase;
import com.pcaokhai.keygeneratorservice.keygen.infra.router.KeyGenController;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Provider side of the {@code GET /generate} contract with shortener-service's
 * {@code KeyGenClient}. Both sides read the same fixture in {@code docs/contracts/} — see
 * {@code docs/contracts/README.md}. shortener-service's
 * {@code KeyGenClientContractTest} feeds this exact JSON into the client; this test asserts
 * the controller actually produces it. A shape change on either side that isn't mirrored on
 * the other breaks one of the two tests.
 */
class KeyGenControllerContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void generateResponseMatchesSharedFixture() throws IOException {
        JsonNode fixture = MAPPER.readTree(readFixture());
        String shortKey = fixture.get("shortKey").asText();

        KeyGenUseCase keyGenUseCase = mock(KeyGenUseCase.class);
        when(keyGenUseCase.generateUniqueKey()).thenReturn(shortKey);
        KeyGenController controller = new KeyGenController(keyGenUseCase);

        ResponseEntity<Map<String, String>> response = controller.generate();

        JsonNode actual = MAPPER.valueToTree(response.getBody());
        assertEquals(fixture, actual);
    }

    private static String readFixture() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("could not locate repo root from " + Path.of("").toAbsolutePath());
        }
        return Files.readString(dir.resolve("docs/contracts/keygen-generate-response.json"));
    }
}
