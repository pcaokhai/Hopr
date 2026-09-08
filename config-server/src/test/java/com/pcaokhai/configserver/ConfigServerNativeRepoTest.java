package com.pcaokhai.configserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConfigServerNativeRepoTest {

    @LocalServerPort
    private int port;

    @Test
    void servesKeygenServiceConfig() throws Exception {
        // Config Server does not resolve placeholders itself — resolution happens
        // client-side, in each consuming service's own container environment
        // (see ADR 0002).
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + port + "/keygen-service/default"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.body()).contains("\"server.port\":\"${KEYGEN_SERVER_PORT}\"");
    }
}
