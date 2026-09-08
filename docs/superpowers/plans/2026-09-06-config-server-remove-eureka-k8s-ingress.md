# Config Server, Eureka Removal, k8s Ingress Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Spring Cloud Config Server that centralizes all services' configuration, remove Eureka entirely (code + all three deploy targets) in favor of native platform DNS/discovery, and replace the k8s nginx pod with a native Ingress.

**Architecture:** A new `config-server` Gradle module (native/file-backed Spring Cloud Config Server, port 8888) serves per-service YAML to `keygen-service`, `shortener-service`, `resolver-service` via `spring.config.import=configserver:http://config-server:8888` (static URL, same pattern Eureka's `defaultZone` used). Eureka is deleted from the codebase and all three deploy targets (Compose, Swarm, k8s); the one real Eureka-dependent call site (`shortener-service` → `keygen-service`) is rewritten to a literal `http://keygen-service:8081` URL. The k8s Helm chart drops its hand-rolled `nginx` Deployment in favor of `ingress-nginx` + a native `Ingress` resource.

**Tech Stack:** Spring Cloud Config Server/Client (`spring-cloud-dependencies:2025.1.2` BOM, already in use), Spring Retry, Gradle multi-module, Docker Compose, Docker Swarm, Helm, `kind` + `ingress-nginx`.

**Spec:** `docs/ADR/0002-remove-eureka-config-server-k8s-ingress.md`

## Global Constraints

- Config Server backend is **native** (file-based, `config-server/src/main/resources/config-repo/`) — no Git repo.
- Services connect to Config Server via the **static URL** `http://config-server:8888` — never via discovery.
- Secrets (`MONGO_PASSWORD`/`MONGO_URI`, `REDIS_PASSWORD`) are **not** moved into Config Server files — they stay as `${ENV_VAR}` placeholders resolved by each client's own container environment, exactly as today.
- Every consuming service's `spring.config.import` must be guarded with `spring.config.activate.on-profile: "!test"` so tests never contact a live Config Server.
- Every consuming service must set `spring.cloud.config.fail-fast: true` with `spring.cloud.config.retry.*` so it tolerates Config Server not being ready yet at container start.
- `resolver-service` requires no Eureka-removal code changes — it was already Eureka-free (`register-with-eureka: false`, `fetch-registry: false`).
- `keygen.service.url` (the literal replacement for Eureka-resolved `http://keygen-service`) must be `http://keygen-service:8081` — this hostname is identical across Compose, Swarm, and k8s Service names.
- Docker Compose's `api-gateway` (nginx) is **not** touched — Compose has no Ingress concept; only the k8s Helm chart's gateway is replaced.
- `kind` has no built-in Ingress Controller — `ingress-nginx`'s official `kind`-flavored manifest must be installed explicitly, and the `kind` node needs the `ingress-ready=true` label.

---

## File Structure

```
config-server/                                    # Task 1 (new module)
  build.gradle.kts
  Dockerfile
  src/main/java/com/pcaokhai/configserver/ConfigServerApplication.java
  src/main/resources/application.yml
  src/main/resources/config-repo/
    application.yml
    keygen-service.yml
    shortener-service.yml
    resolver-service.yml
  src/test/java/com/pcaokhai/configserver/ConfigServerApplicationTests.java

keygen-service/                                    # Task 2
  build.gradle.kts                                 (add config client + retry deps)
  src/main/resources/application.yml                (rewritten: bootstrap-only)
  src/main/java/.../KeyGeneratorServiceApplication.java  (remove @EnableDiscoveryClient)
  src/main/java/.../config/EurekaClientConfig.java  (deleted)
  src/test/java/.../unit_tests/config/EurekaClientConfigTest.java  (deleted)

shortener-service/                                 # Task 3
  build.gradle.kts                                 (add config client + retry deps)
  src/main/resources/application.yml                (rewritten: bootstrap-only)
  src/main/java/.../UrlShortenerApplication.java    (remove @EnableDiscoveryClient)
  src/main/java/.../config/EurekaClientConfig.java  (deleted)
  src/main/java/.../config/WebClientConfig.java     (remove @LoadBalanced)
  src/main/java/.../web/keygen/KeyGenClient.java    (hard-coded URL via injected property)
  src/test/java/.../unit_tests/urlshort/config/EurekaClientConfigTest.java  (deleted)
  src/test/java/.../unit_tests/urlshort/web/KeyGenClientTest.java  (updated constructor+URL)
  src/test/java/.../integration_tests/urlshort/config/BaseIntegrationTest.java  (add @ActiveProfiles)
  src/test/resources/application-test.yml           (remove Eureka excludes)

resolver-service/                                  # Task 4
  build.gradle.kts                                 (add config client + retry deps)
  src/main/resources/application.yml                (rewritten: bootstrap-only)

settings.gradle.kts                                # Task 5 (remove eureka, add config-server)
build.gradle.kts                                   # Task 5 (bootApps set, remove eureka-client dep)
eureka/                                             # Task 5 (deleted entirely)

docker-compose.yml                                 # Task 6
.env                                                # Task 6 (remove EUREKA_*)

stack.yml                                           # Task 7

k8s/hopr-chart/values.yaml                          # Task 8 (remove eurekaUrl, gateway.nodePort)
k8s/hopr-chart/templates/configmap.yaml             # Task 8 (remove EUREKA_CLIENT_SERVICEURL_DEFAULTZONE)
k8s/hopr-chart/templates/eureka.yaml                # Task 8 (deleted)
k8s/hopr-chart/templates/config-server.yaml         # Task 8 (new)
k8s/build-and-load.sh                               # Task 8 (add config-server, remove eureka)
k8s/deploy.sh                                       # Task 8 (remove eureka rollout wait)

k8s/kind-config.yaml                                # Task 9 (ingress-ready label, 80/443 mappings)
k8s/hopr-chart/templates/api-gateway.yaml           # Task 9 (deleted)
k8s/hopr-chart/files/nginx.conf                     # Task 9 (deleted)
k8s/hopr-chart/templates/ingress.yaml               # Task 9 (new)
k8s/deploy.sh                                       # Task 9 (install ingress-nginx, drop api-gateway wait)

k8s/README.md                                       # Task 11 (docs)
README.md                                           # Task 11 (docs)
```

---

### Task 1: Config Server module

**Files:**
- Create: `config-server/build.gradle.kts`
- Create: `config-server/Dockerfile`
- Create: `config-server/src/main/java/com/pcaokhai/configserver/ConfigServerApplication.java`
- Create: `config-server/src/main/resources/application.yml`
- Create: `config-server/src/main/resources/config-repo/application.yml`
- Create: `config-server/src/main/resources/config-repo/keygen-service.yml`
- Create: `config-server/src/main/resources/config-repo/shortener-service.yml`
- Create: `config-server/src/main/resources/config-repo/resolver-service.yml`
- Create: `config-server/src/test/java/com/pcaokhai/configserver/ConfigServerApplicationTests.java`
- Modify: `settings.gradle.kts` (add `config-server` to `include(...)` — see Task 5 for full Gradle wiring; for this task, add it standalone so the module builds)
- Modify: `build.gradle.kts` (add `config-server` to `bootApps` set — same caveat as above)

**Interfaces:**
- Produces: an HTTP endpoint `GET http://localhost:8888/{application}/default` returning that application's merged YAML config as JSON (Spring Cloud Config's standard `/{application}/{profile}` endpoint) — Tasks 2-4 depend on this being served correctly for `keygen-service`, `shortener-service`, `resolver-service`.

- [ ] **Step 1: Wire the module into the Gradle build**

Edit `settings.gradle.kts` — change:
```kotlin
include("eureka", "common", "shortener-service", "resolver-service", "keygen-service")
```
to:
```kotlin
include("eureka", "common", "shortener-service", "resolver-service", "keygen-service", "config-server")
```
(The `eureka` module removal happens in Task 5 — for now, just add `config-server` alongside it so nothing else breaks.)

Edit `build.gradle.kts` — change:
```kotlin
val bootApps = setOf("eureka", "shortener-service", "resolver-service", "keygen-service")
```
to:
```kotlin
val bootApps = setOf("eureka", "shortener-service", "resolver-service", "keygen-service", "config-server")
```

- [ ] **Step 2: Write `config-server/build.gradle.kts`**

```kotlin
dependencies {
    implementation("org.springframework.cloud:spring-cloud-config-server")
}

tasks.named<Jar>("jar") {
    enabled = false
}
```

- [ ] **Step 3: Write the Dockerfile**

```dockerfile
# config-server/Dockerfile
FROM eclipse-temurin:25-jre
VOLUME /tmp
COPY build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

- [ ] **Step 4: Write the main application class**

```java
// config-server/src/main/java/com/pcaokhai/configserver/ConfigServerApplication.java
package com.pcaokhai.configserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}
```

- [ ] **Step 5: Write the Config Server's own bootstrap config**

```yaml
# config-server/src/main/resources/application.yml
server:
  port: 8888

spring:
  application:
    name: config-server
  profiles:
    active: native
  cloud:
    config:
      server:
        native:
          search-locations: classpath:/config-repo
```

- [ ] **Step 6: Write the shared config-repo file**

```yaml
# config-server/src/main/resources/config-repo/application.yml
spring:
  data:
    redis:
      password: ${REDIS_PASSWORD}
      client-type: lettuce
      timeout: ${REDIS_TIMEOUT}
  cache:
    type: ${SPRING_CACHE_TYPE}

springdoc:
  swagger-ui:
    path: /swagger-ui.html
```

- [ ] **Step 7: Write `keygen-service.yml`**

```yaml
# config-server/src/main/resources/config-repo/keygen-service.yml
server:
  port: ${KEYGEN_SERVER_PORT}

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: always
```

- [ ] **Step 8: Write `shortener-service.yml`**

```yaml
# config-server/src/main/resources/config-repo/shortener-service.yml
server:
  port: ${SHORTENER_SERVER_PORT}

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: always

spring:
  data:
    mongodb:
      uri: ${MONGO_URI}
    redis:
      port: ${REDIS_PORT}
      cluster:
        nodes:
          - ${REDIS_NODE_1}
          - ${REDIS_NODE_2}
          - ${REDIS_NODE_3}
          - ${REDIS_NODE_4}
          - ${REDIS_NODE_5}
          - ${REDIS_NODE_6}

shortener:
  domain: ${SHORTENER_DOMAIN}

keygen:
  service:
    url: http://keygen-service:8081
```

Note: the old `spring.mongodb.uri: ${MONGO_URI}` duplicate (alongside the
correct `spring.data.mongodb.uri`) is dropped here — `spring.mongodb.uri`
is not a real Spring Boot property and did nothing.

- [ ] **Step 9: Write `resolver-service.yml`**

```yaml
# config-server/src/main/resources/config-repo/resolver-service.yml
server:
  port: ${RESOLVER_SERVER_PORT}

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: always

spring:
  data:
    mongodb:
      uri: ${MONGO_URI}
    redis:
      port: ${REDIS_PORT}
      cluster:
        nodes:
          - ${REDIS_NODE_1}
          - ${REDIS_NODE_2}
          - ${REDIS_NODE_3}
          - ${REDIS_NODE_4}
          - ${REDIS_NODE_5}
          - ${REDIS_NODE_6}
```

- [ ] **Step 10: Write a context-load test**

```java
// config-server/src/test/java/com/pcaokhai/configserver/ConfigServerApplicationTests.java
package com.pcaokhai.configserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ConfigServerApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 11: Write a functional test that proves the native repo serves real content**

```java
// config-server/src/test/java/com/pcaokhai/configserver/ConfigServerNativeRepoTest.java
package com.pcaokhai.configserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "KEYGEN_SERVER_PORT=8081",
        "REDIS_PASSWORD=",
        "REDIS_TIMEOUT=6000",
        "SPRING_CACHE_TYPE=redis"
})
class ConfigServerNativeRepoTest {

    @LocalServerPort
    private int port;

    private final TestRestTemplate rest = new TestRestTemplate();

    @Test
    void servesKeygenServiceConfig() {
        String body = rest.getForObject(
                "http://localhost:" + port + "/keygen-service/default", String.class);

        assertThat(body).contains("\"server.port\":\"8081\"");
    }
}
```

- [ ] **Step 12: Run the tests**

Run: `./gradlew :config-server:test`
Expected: both tests pass — `contextLoads` confirms the app boots with
`@EnableConfigServer`, `servesKeygenServiceConfig` confirms the native
repo is actually being read and the `${KEYGEN_SERVER_PORT}` env var is
resolved through the Config Server response.

- [ ] **Step 13: Commit**

```bash
git add config-server settings.gradle.kts build.gradle.kts
git commit -m "feat: add Spring Cloud Config Server module"
```

---

### Task 2: Migrate keygen-service off Eureka onto Config Server

**Files:**
- Modify: `keygen-service/build.gradle.kts`
- Modify: `keygen-service/src/main/resources/application.yml`
- Modify: `keygen-service/src/main/java/com/pcaokhai/keygeneratorservice/KeyGeneratorServiceApplication.java`
- Delete: `keygen-service/src/main/java/com/pcaokhai/keygeneratorservice/config/EurekaClientConfig.java`
- Delete: `keygen-service/src/test/java/com/pcaokhai/keygeneratorservice/unit_tests/config/EurekaClientConfigTest.java`

**Interfaces:**
- Consumes: Config Server at `http://config-server:8888` (Task 1) — will not actually be reachable until Task 6 (Compose) wires up the container, but the `!test` guard means this task's own tests never need it live.

- [ ] **Step 1: Add the Config client + retry dependencies**

Edit `keygen-service/build.gradle.kts`, add to the `dependencies` block:
```kotlin
    implementation("org.springframework.cloud:spring-cloud-starter-config")
    implementation("org.springframework.retry:spring-retry")
```
Full resulting file:
```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.cloud:spring-cloud-starter-config")
    implementation("org.springframework.retry:spring-retry")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.testcontainers:testcontainers")
}

tasks.named<Test>("test") {
    include("**/*Test.class")
}

tasks.named<Jar>("jar") {
    enabled = false
}
```

- [ ] **Step 2: Rewrite the bootstrap `application.yml`**

```yaml
# keygen-service/src/main/resources/application.yml
spring:
  application:
    name: keygen-service
---
spring:
  config:
    activate:
      on-profile: "!test"
    import: "configserver:http://config-server:8888"
  cloud:
    config:
      fail-fast: true
      retry:
        max-attempts: 10
        initial-interval: 2000
        max-interval: 10000
        multiplier: 1.5
```

- [ ] **Step 3: Remove `@EnableDiscoveryClient`**

Edit `keygen-service/src/main/java/com/pcaokhai/keygeneratorservice/KeyGeneratorServiceApplication.java`:
```java
package com.pcaokhai.keygeneratorservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class KeyGeneratorServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(KeyGeneratorServiceApplication.class, args);
    }

}
```

- [ ] **Step 4: Delete the Eureka-specific config class and its test**

```bash
rm keygen-service/src/main/java/com/pcaokhai/keygeneratorservice/config/EurekaClientConfig.java
rm keygen-service/src/test/java/com/pcaokhai/keygeneratorservice/unit_tests/config/EurekaClientConfigTest.java
```

- [ ] **Step 5: Run the keygen-service test suite**

Run: `./gradlew :keygen-service:test`
Expected: BUILD SUCCESSFUL — `KeyGeneratorServiceApplicationTests` (which
`@ActiveProfiles("test")`, so `!test` import is skipped) still passes;
no compile errors from the deleted `EurekaClientConfig` (nothing else
referenced it).

- [ ] **Step 6: Commit**

```bash
git add keygen-service
git commit -m "feat: migrate keygen-service off Eureka onto Config Server"
```

---

### Task 3: Migrate shortener-service off Eureka onto Config Server

**Files:**
- Modify: `shortener-service/build.gradle.kts`
- Modify: `shortener-service/src/main/resources/application.yml`
- Modify: `shortener-service/src/main/java/com/pcaokhai/urlshortenerservice/UrlShortenerApplication.java`
- Modify: `shortener-service/src/main/java/com/pcaokhai/urlshortenerservice/config/WebClientConfig.java`
- Modify: `shortener-service/src/main/java/com/pcaokhai/urlshortenerservice/web/keygen/KeyGenClient.java`
- Modify: `shortener-service/src/test/java/com/pcaokhai/urlshortenerservice/unit_tests/urlshort/web/KeyGenClientTest.java`
- Modify: `shortener-service/src/test/java/com/pcaokhai/urlshortenerservice/integration_tests/urlshort/config/BaseIntegrationTest.java`
- Modify: `shortener-service/src/test/resources/application-test.yml`
- Delete: `shortener-service/src/main/java/com/pcaokhai/urlshortenerservice/config/EurekaClientConfig.java`
- Delete: `shortener-service/src/test/java/com/pcaokhai/urlshortenerservice/unit_tests/urlshort/config/EurekaClientConfigTest.java`

**Interfaces:**
- Consumes: `keygen.service.url` property (Task 1's `config-repo/shortener-service.yml`, value `http://keygen-service:8081`) — injected into `KeyGenClient`'s constructor.
- Produces: `KeyGenClient(WebClient.Builder builder, String keygenServiceUrl)` — the new 2-arg constructor other code/tests must use.

- [ ] **Step 1: Add the Config client + retry dependencies**

Edit `shortener-service/build.gradle.kts`, add to `dependencies`:
```kotlin
    implementation("org.springframework.cloud:spring-cloud-starter-config")
    implementation("org.springframework.retry:spring-retry")
```
Full resulting file:
```kotlin
dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework:spring-webflux")
    implementation("org.springframework.cloud:spring-cloud-starter-config")
    implementation("org.springframework.retry:spring-retry")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("org.testcontainers:testcontainers-mongodb")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}

tasks.named<Test>("test") {
    include("**/*Test.class")
}

tasks.named<Jar>("jar") {
    enabled = false
}
```

- [ ] **Step 2: Rewrite the bootstrap `application.yml`**

```yaml
# shortener-service/src/main/resources/application.yml
spring:
  application:
    name: shortener-service
---
spring:
  config:
    activate:
      on-profile: "!test"
    import: "configserver:http://config-server:8888"
  cloud:
    config:
      fail-fast: true
      retry:
        max-attempts: 10
        initial-interval: 2000
        max-interval: 10000
        multiplier: 1.5
```

- [ ] **Step 3: Remove `@EnableDiscoveryClient`**

Edit `shortener-service/src/main/java/com/pcaokhai/urlshortenerservice/UrlShortenerApplication.java`:
```java
package com.pcaokhai.urlshortenerservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@SpringBootApplication(scanBasePackages = {"com.pcaokhai.common", "com.pcaokhai.urlshortenerservice"})
@EnableMongoRepositories(basePackages = "com.pcaokhai.common.url.repository")
public class UrlShortenerApplication {

	public static void main(String[] args) {
		SpringApplication.run(UrlShortenerApplication.class, args);
	}

}
```

- [ ] **Step 4: Simplify `WebClientConfig`**

```java
// shortener-service/src/main/java/com/pcaokhai/urlshortenerservice/config/WebClientConfig.java
package com.pcaokhai.urlshortenerservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Configuration class providing a plain WebClient.Builder.
 * Downstream service URLs are literal (see KeyGenClient), resolved by the
 * container platform's own DNS (Compose embedded DNS / k8s CoreDNS).
 */
@Configuration
public class WebClientConfig {
    @Bean
    public WebClient.Builder webClientBuild() {
        return WebClient.builder();
    }
}
```

- [ ] **Step 5: Update `KeyGenClient` to use an injected literal URL**

```java
// shortener-service/src/main/java/com/pcaokhai/urlshortenerservice/web/keygen/KeyGenClient.java
package com.pcaokhai.urlshortenerservice.web.keygen;

import com.pcaokhai.urlshortenerservice.urlshort.exception.KeygenServiceUnvailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.Optional;

/**
 * Client for interacting with the Keygen service to generate short keys.
 * This component uses WebClient to make HTTP requests to the Keygen service.
 */
@Component
public class KeyGenClient {
    private final WebClient webClient;
    private final String keygenServiceUrl;

    public KeyGenClient(WebClient.Builder builder, @Value("${keygen.service.url}") String keygenServiceUrl) {
        this.webClient = builder.build();
        this.keygenServiceUrl = keygenServiceUrl;
    }

    public String generateKey() {
        return callKeygenService()
                .orElseThrow(() -> new KeygenServiceUnvailableException("Keygen Service Is Unavailable"));
    }

    private Optional<String> callKeygenService() {
        try {
            return Optional.ofNullable(webClient.get()
                    .uri(keygenServiceUrl + "/generate")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .map(response -> (String) response.get("shortKey"))
                    .block());
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 6: Update `KeyGenClientTest` for the new constructor and URL**

```java
// shortener-service/src/test/java/com/pcaokhai/urlshortenerservice/unit_tests/urlshort/web/KeyGenClientTest.java
package com.pcaokhai.urlshortenerservice.unit_tests.urlshort.web;

import com.pcaokhai.urlshortenerservice.urlshort.exception.KeygenServiceUnvailableException;
import com.pcaokhai.urlshortenerservice.web.keygen.KeyGenClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@SuppressWarnings("rawtypes")
public class KeyGenClientTest {

    private static final String KEYGEN_URL = "http://keygen-service:8081";

    @Mock private WebClient.Builder mockBuilder;
    @Mock private WebClient mockWebClient;

    @Mock private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;
    @Mock private WebClient.RequestHeadersSpec requestHeadersSpec;
    @Mock private WebClient.ResponseSpec responseSpec;

    private KeyGenClient keyGenClient;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(mockBuilder.build()).thenReturn(mockWebClient);
        keyGenClient = new KeyGenClient(mockBuilder, KEYGEN_URL);
    }

    @Test
    void testGenerateKey_Success() {
        Map<String, String> responseMap = new HashMap<>();
        responseMap.put("shortKey", "abc123");

        when(mockWebClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(KEYGEN_URL + "/generate")).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(Map.class)).thenReturn(Mono.just(responseMap));

        String key = keyGenClient.generateKey();

        assertEquals("abc123", key);
    }

    @Test
    void testGenerateKey_ServiceUnavailable() {
        when(mockWebClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(KEYGEN_URL + "/generate")).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(Map.class)).thenThrow(new RuntimeException("Service error"));

        KeygenServiceUnvailableException ex = assertThrows(
                KeygenServiceUnvailableException.class,
                () -> keyGenClient.generateKey()
        );

        assertEquals("Keygen Service Is Unavailable", ex.getMessage());
    }
}
```

- [ ] **Step 7: Delete the Eureka-specific config class and its test**

```bash
rm shortener-service/src/main/java/com/pcaokhai/urlshortenerservice/config/EurekaClientConfig.java
rm shortener-service/src/test/java/com/pcaokhai/urlshortenerservice/unit_tests/urlshort/config/EurekaClientConfigTest.java
```

- [ ] **Step 8: Fix `BaseIntegrationTest` so it also skips Config Server import**

`BaseIntegrationTest` boots a full `@SpringBootTest` context via
Testcontainers but never activates the `test` profile, so the `!test`
guard in `application.yml` would (incorrectly) try to reach a live Config
Server. Add `@ActiveProfiles("test")`:

```java
// shortener-service/src/test/java/com/pcaokhai/urlshortenerservice/integration_tests/urlshort/config/BaseIntegrationTest.java
package com.pcaokhai.urlshortenerservice.integration_tests.urlshort.config;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {
    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");


    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("spring.redis.cluster.nodes", List::of);
        reg.add("spring.data.mongodb.uri", mongo::getConnectionString);
    }
}
```

- [ ] **Step 9: Remove the now-dangling Eureka excludes from the test profile**

`application-test.yml` currently excludes
`org.springframework.cloud.netflix.eureka.EurekaClientAutoConfiguration`
and `EurekaDiscoveryClientConfiguration` by class name. Once the
`spring-cloud-starter-netflix-eureka-client` dependency is removed
(Task 5), those classes won't exist on the classpath, and
`spring.autoconfigure.exclude` referencing a missing class throws at
context startup. Remove that block and the now-meaningless
`spring.cloud.discovery.enabled: false`:

```yaml
# shortener-service/src/test/resources/application-test.yml
server:
  port: 8081

spring:
  application:
    name: shortener-service
  data:
    mongodb:
      host: localhost
      port: 27017
      uri: mongodb://localhost:27017/shortener-test
      username: root
      password: test
      database: shortener-test

    redis:
      host: localhost
      port: 6379
      password: test
      client-type: lettuce
      timeout: 6
      cluster:
        nodes:
          - 127.0.0.1:6379
          - 127.0.0.1:6379
          - 127.0.0.1:6379

  cache:
    type: redis

shortener:
  domain: http://localhost:80/

keygen:
  service:
    url: http://localhost:8081
```

(`keygen.service.url` is added here because `KeyGenClientTest` doesn't
need it — it mocks `KeyGenClient` directly — but any future
`@SpringBootTest` that wires the real bean would otherwise fail to
resolve `${keygen.service.url}` with no Config Server available in
tests. A harmless local placeholder value is enough.)

- [ ] **Step 10: Run the shortener-service test suite**

Run: `./gradlew :shortener-service:test`
Expected: BUILD SUCCESSFUL — unit tests (`KeyGenClientTest`,
`WebClientConfigTest` unchanged) and integration tests
(`BaseIntegrationTest` subclasses, now with `test` profile active) all
pass; no `ClassNotFoundException` from the removed autoconfigure excludes.

- [ ] **Step 11: Commit**

```bash
git add shortener-service
git commit -m "feat: migrate shortener-service off Eureka onto Config Server"
```

---

### Task 4: Migrate resolver-service onto Config Server

**Files:**
- Modify: `resolver-service/build.gradle.kts`
- Modify: `resolver-service/src/main/resources/application.yml`

**Interfaces:**
- Consumes: `config-repo/resolver-service.yml` (Task 1).
- No Eureka code to remove — `resolver-service` never depended on it.

- [ ] **Step 1: Add the Config client + retry dependencies**

Edit `resolver-service/build.gradle.kts`, add to `dependencies`:
```kotlin
    implementation("org.springframework.cloud:spring-cloud-starter-config")
    implementation("org.springframework.retry:spring-retry")
```
Full resulting `dependencies` block (rest of the file — the jacoco
overrides at the bottom — is unchanged):
```kotlin
dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.cloud:spring-cloud-starter-config")
    implementation("org.springframework.retry:spring-retry")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}
```

- [ ] **Step 2: Rewrite the bootstrap `application.yml`**

```yaml
# resolver-service/src/main/resources/application.yml
spring:
  application:
    name: resolver-service
---
spring:
  config:
    activate:
      on-profile: "!test"
    import: "configserver:http://config-server:8888"
  cloud:
    config:
      fail-fast: true
      retry:
        max-attempts: 10
        initial-interval: 2000
        max-interval: 10000
        multiplier: 1.5
```

Note: the old `eureka.client.register-with-eureka: false` /
`fetch-registry: false` and `springdoc.swagger-ui.path` entries are
dropped from the local file — the Eureka lines are meaningless once the
client dependency is gone (Task 5), and `springdoc.swagger-ui.path` now
lives in `config-server/src/main/resources/config-repo/application.yml`
(Task 1, Step 6) as a shared default.

- [ ] **Step 3: Run the resolver-service test suite**

Run: `./gradlew :resolver-service:test`
Expected: BUILD SUCCESSFUL — `ResolverServiceApplicationTests`
(`@ActiveProfiles("test")`) passes with the `!test` guard skipping config
import.

- [ ] **Step 4: Commit**

```bash
git add resolver-service
git commit -m "feat: migrate resolver-service onto Config Server"
```

---

### Task 5: Delete the eureka module and its dependency from the shared build

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Delete: `eureka/` (entire directory)

**Interfaces:**
- Consumes: nothing further depends on Eureka after Tasks 2-4 — this task is safe to run now.
- Produces: a repo-wide build with zero Eureka references, verified by a full `./gradlew build`.

- [ ] **Step 1: Remove `eureka` from `settings.gradle.kts`**

```kotlin
rootProject.name = "url-shortener"

include("common", "shortener-service", "resolver-service", "keygen-service", "config-server")
```

- [ ] **Step 2: Remove `eureka` from `bootApps` and the shared eureka-client dependency**

Edit `build.gradle.kts`:
```kotlin
val bootApps = setOf("shortener-service", "resolver-service", "keygen-service", "config-server")
```

Remove this line from the `dependencies` block inside `subprojects { ... }`:
```kotlin
        "implementation"("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
```

- [ ] **Step 3: Delete the eureka module**

```bash
rm -rf eureka
```

- [ ] **Step 4: Run the full build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL — no module references `eureka` or
`spring-cloud-starter-netflix-eureka-client` anymore, `config-server` and
all three app services compile.

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL — full test suite (all modules) passes.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: remove Eureka module and dependency entirely"
```

---

### Task 6: docker-compose.yml — remove eureka-server, add config-server

**Files:**
- Modify: `docker-compose.yml`
- Modify: `.env`

**Interfaces:**
- Consumes: `config-server` image built from `config-server/Dockerfile` (Task 1).
- Produces: a `config-server` Compose service other services' `depends_on` reference.

- [ ] **Step 1: Remove the `eureka-server` service and add `config-server`**

Edit `docker-compose.yml` — replace the `eureka-server:` block:
```yaml
  eureka-server:
    build:
      context: ./eureka
      dockerfile: Dockerfile
    image: hopr/eureka:latest
    container_name: hopr-eureka-server
    environment:
      JAVA_OPTS: "-XX:+UseContainerSupport -XX:MaxRAMPercentage=25.0"
    env_file:
      - .env
    ports:
      - "8761:8761"
    networks:
      - default
```
with:
```yaml
  config-server:
    build:
      context: ./config-server
      dockerfile: Dockerfile
    image: hopr/config-server:latest
    container_name: hopr-config-server
    environment:
      JAVA_OPTS: "-XX:+UseContainerSupport -XX:MaxRAMPercentage=25.0"
    ports:
      - "8888:8888"
    networks:
      - default
```

- [ ] **Step 2: Point the app services at `config-server` instead of `eureka-server`**

In the `keygen-service` block, replace:
```yaml
    depends_on:
      - eureka-server
```
with:
```yaml
    depends_on:
      - config-server
```

In the `shortener-service` block, replace:
```yaml
    depends_on:
      - eureka-server
      - keygen-service
      - mongodb
      - redis-cluster-init
```
with:
```yaml
    depends_on:
      - config-server
      - keygen-service
      - mongodb
      - redis-cluster-init
```

In the `resolver-service` block, replace:
```yaml
    depends_on:
      - mongodb
      - redis-cluster-init
```
with:
```yaml
    depends_on:
      - config-server
      - mongodb
      - redis-cluster-init
```

- [ ] **Step 3: Remove the now-unused Eureka variables from `.env`**

```
# Eureka
EUREKA_SERVER_HOST=eureka-server
EUREKA_CLIENT_SERVICEURL_DEFAULTZONE=http://eureka-server:8761/eureka/
EUREKA_SERVER_PORT=8761

```
Delete this block entirely from `.env` (the file starts directly with the
`# MongoDB` section afterward).

- [ ] **Step 4: Bring up the stack and verify end-to-end**

Run:
```bash
docker compose up --build -d
```
Wait ~30s for the JVMs to boot and Config Server retries to succeed, then:
```bash
curl -s -X POST http://localhost/shorten -H 'Content-Type: application/json' -d '{"longUrl":"https://example.com"}'
```
Expected: `{"shortUrl":"http://localhost/<KEY>"}` (not a 503 — proves
`shortener-service` fetched `keygen.service.url` from Config Server and
successfully called `keygen-service` directly, with no Eureka involved).

```bash
curl -sI http://localhost/<KEY>
```
Expected: `HTTP/1.1 307` with `Location: https://example.com`.

```bash
docker compose ps
```
Expected: no `eureka-server` container listed; `config-server` is
`Up`/healthy.

- [ ] **Step 5: Tear down**

```bash
docker compose down
```

- [ ] **Step 6: Commit**

```bash
git add docker-compose.yml .env
git commit -m "feat: replace eureka-server with config-server in docker-compose"
```

---

### Task 7: stack.yml — remove eureka-server, add config-server

**Files:**
- Modify: `stack.yml`

**Interfaces:**
- Consumes: same `hopr/config-server:latest` image built in Task 6.

- [ ] **Step 1: Remove the `eureka-server` service block**

Delete this entire block from `stack.yml`:
```yaml
  eureka-server:
    image: hopr/eureka:latest
    environment:
      JAVA_OPTS: "-XX:+UseContainerSupport -XX:MaxRAMPercentage=25.0"
    env_file:
      - .env
    ports:
      - "8761:8761"
    deploy:
      resources:
        reservations:
          memory: 1G
          cpus: '0.5'
      mode: replicated
      replicas: 1
    networks:
      - default
```

- [ ] **Step 2: Add a `config-server` service block in its place**

```yaml
  config-server:
    image: hopr/config-server:latest
    environment:
      JAVA_OPTS: "-XX:+UseContainerSupport -XX:MaxRAMPercentage=25.0"
    ports:
      - "8888:8888"
    deploy:
      resources:
        reservations:
          memory: 512M
          cpus: '0.5'
      mode: replicated
      replicas: 1
    networks:
      - default
```

- [ ] **Step 3: Verify no other reference to `eureka-server` remains**

Run: `grep -n "eureka" stack.yml`
Expected: no output (0 matches).

- [ ] **Step 4: Commit**

```bash
git add stack.yml
git commit -m "feat: replace eureka-server with config-server in stack.yml"
```

---

### Task 8: k8s Helm chart — remove eureka, add config-server

**Files:**
- Modify: `k8s/hopr-chart/values.yaml`
- Modify: `k8s/hopr-chart/templates/configmap.yaml`
- Delete: `k8s/hopr-chart/templates/eureka.yaml`
- Create: `k8s/hopr-chart/templates/config-server.yaml`
- Modify: `k8s/build-and-load.sh`
- Modify: `k8s/deploy.sh`

**Interfaces:**
- Produces: `Service/config-server` (ClusterIP, port 8888) in namespace `hopr` — matches the static URL `http://config-server:8888` hard-coded into every service's `application.yml` (Tasks 2-4).

- [ ] **Step 1: Remove Eureka values, keep the rest of `values.yaml`**

```yaml
# k8s/hopr-chart/values.yaml
namespace: hopr

image:
  tag: local
  pullPolicy: Never

mongodb:
  image: mongo:7.0
  rootUsername: root
  rootPassword: password
  database: Hopr

redis:
  # Must match the release name used for `helm install <name> bitnami/redis-cluster`
  releaseName: hopr-redis
  port: "6379"
  password: ""
  timeout: "6000"
  nodeCount: 6

config:
  shortenerServerPort: "8080"
  resolverServerPort: "8083"
  keygenServerPort: "8081"
  shortenerDomain: "http://localhost:8888/"

gateway:
  nodePort: 30080
```

(`config.eurekaUrl` is removed. `gateway.nodePort` is removed in Task 9,
not here — leave it for now so Task 9's diff is self-contained.)

- [ ] **Step 2: Remove `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` from the ConfigMap template**

```yaml
# k8s/hopr-chart/templates/configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: hopr-config
  namespace: {{ .Values.namespace }}
data:
  {{- range $i := until (int .Values.redis.nodeCount) }}
  REDIS_NODE_{{ add $i 1 }}: "{{ $.Values.redis.releaseName }}-redis-cluster-{{ $i }}.{{ $.Values.redis.releaseName }}-redis-cluster-headless:6379"
  {{- end }}
  REDIS_PORT: {{ .Values.redis.port | quote }}
  REDIS_TIMEOUT: {{ .Values.redis.timeout | quote }}
  SPRING_CACHE_TYPE: "redis"
  SHORTENER_SERVER_PORT: {{ .Values.config.shortenerServerPort | quote }}
  RESOLVER_SERVER_PORT: {{ .Values.config.resolverServerPort | quote }}
  KEYGEN_SERVER_PORT: {{ .Values.config.keygenServerPort | quote }}
  SHORTENER_DOMAIN: {{ .Values.config.shortenerDomain | quote }}
```

- [ ] **Step 3: Delete the Eureka template**

```bash
rm k8s/hopr-chart/templates/eureka.yaml
```

- [ ] **Step 4: Add the Config Server template**

```yaml
# k8s/hopr-chart/templates/config-server.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: config-server
  namespace: {{ .Values.namespace }}
spec:
  replicas: 1
  selector:
    matchLabels:
      app: config-server
  template:
    metadata:
      labels:
        app: config-server
    spec:
      containers:
        - name: config-server
          image: "hopr/config-server:{{ .Values.image.tag }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - containerPort: 8888
          readinessProbe:
            tcpSocket:
              port: 8888
            initialDelaySeconds: 15
            periodSeconds: 5
          livenessProbe:
            tcpSocket:
              port: 8888
            initialDelaySeconds: 25
            periodSeconds: 10
---
apiVersion: v1
kind: Service
metadata:
  name: config-server
  namespace: {{ .Values.namespace }}
spec:
  selector:
    app: config-server
  ports:
    - port: 8888
      targetPort: 8888
```

(TCP probes, same reasoning as the old `eureka.yaml`: `config-server` has
no Actuator dependency, so no HTTP health endpoint to probe.)

- [ ] **Step 5: Update `build-and-load.sh` to build `config-server` instead of `eureka`**

```bash
#!/usr/bin/env bash
# k8s/build-and-load.sh
set -euo pipefail

cd "$(dirname "$0")/.."

./gradlew :config-server:build :keygen-service:build :shortener-service:build :resolver-service:build -x test

for service in config-server keygen-service shortener-service resolver-service; do
  echo "Building image for $service..."
  docker build -t "hopr/$service:local" "$service/"
  kind load docker-image "hopr/$service:local" --name hopr
done

echo "All images built and loaded into kind cluster 'hopr'."
```

- [ ] **Step 6: Update `deploy.sh`'s rollout wait for `config-server`**

Replace this line in `k8s/deploy.sh`:
```bash
kubectl rollout status deployment/eureka-server -n hopr --timeout=120s
```
with:
```bash
kubectl rollout status deployment/config-server -n hopr --timeout=120s
```

- [ ] **Step 7: Redeploy the cluster and verify**

Run:
```bash
kind delete cluster --name hopr
./k8s/deploy.sh
```
Expected: script completes; `kubectl get pods -n hopr` shows no
`eureka-server` pod and a `Running` `config-server` pod.

```bash
kubectl exec -n hopr deploy/shortener-service -- curl -sf http://localhost:8080/actuator/health
```
Expected: `"status":"UP"` — proves `shortener-service` successfully
fetched its config (including `MONGO_URI`, Redis nodes) from
`config-server` inside the cluster.

- [ ] **Step 8: Commit**

```bash
git add k8s/hopr-chart k8s/build-and-load.sh k8s/deploy.sh
git commit -m "feat: replace eureka with config-server in k8s Helm chart"
```

---

### Task 9: k8s — replace the nginx pod with an Ingress

**Files:**
- Modify: `k8s/kind-config.yaml`
- Delete: `k8s/hopr-chart/templates/api-gateway.yaml`
- Delete: `k8s/hopr-chart/files/nginx.conf`
- Create: `k8s/hopr-chart/templates/ingress.yaml`
- Modify: `k8s/hopr-chart/values.yaml`
- Modify: `k8s/deploy.sh`

**Interfaces:**
- Produces: an `Ingress` resource routing `/shorten` and short-key paths to `shortener-service`/`resolver-service`, replacing the NodePort `api-gateway` Service entirely.

- [ ] **Step 1: Update `kind-config.yaml` for `ingress-nginx`**

```yaml
# k8s/kind-config.yaml
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
name: hopr
nodes:
  - role: control-plane
    kubeadmConfigPatches:
      - |
        kind: InitConfiguration
        nodeRegistration:
          kubeletExtraArgs:
            node-labels: "ingress-ready=true"
    extraPortMappings:
      - containerPort: 80
        hostPort: 8888
        protocol: TCP
      - containerPort: 443
        hostPort: 8443
        protocol: TCP
```

(`ingress-nginx`'s `kind`-flavored manifest binds its controller pod to
container ports 80/443 via `hostPort` — this maps those to host ports
8888/8443, keeping the existing "avoid colliding with docker-compose's
port 80" behavior from before.)

- [ ] **Step 2: Delete the nginx gateway template and its bundled config**

```bash
rm k8s/hopr-chart/templates/api-gateway.yaml
rm k8s/hopr-chart/files/nginx.conf
```

- [ ] **Step 3: Remove `gateway.nodePort` from `values.yaml`**

```yaml
# k8s/hopr-chart/values.yaml
namespace: hopr

image:
  tag: local
  pullPolicy: Never

mongodb:
  image: mongo:7.0
  rootUsername: root
  rootPassword: password
  database: Hopr

redis:
  # Must match the release name used for `helm install <name> bitnami/redis-cluster`
  releaseName: hopr-redis
  port: "6379"
  password: ""
  timeout: "6000"
  nodeCount: 6

config:
  shortenerServerPort: "8080"
  resolverServerPort: "8083"
  keygenServerPort: "8081"
  shortenerDomain: "http://localhost:8888/"
```

- [ ] **Step 4: Write the Ingress template**

```yaml
# k8s/hopr-chart/templates/ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: hopr-ingress
  namespace: {{ .Values.namespace }}
  annotations:
    nginx.ingress.kubernetes.io/use-regex: "true"
    nginx.ingress.kubernetes.io/limit-rps: "5"
    nginx.ingress.kubernetes.io/limit-burst-multiplier: "2"
    nginx.ingress.kubernetes.io/enable-cors: "true"
    nginx.ingress.kubernetes.io/cors-allow-origin: "*"
    nginx.ingress.kubernetes.io/cors-allow-methods: "GET, POST, OPTIONS, PUT, DELETE"
    nginx.ingress.kubernetes.io/cors-allow-headers: "Origin, X-Requested-With, Content-Type, Accept, Authorization"
spec:
  ingressClassName: nginx
  rules:
    - http:
        paths:
          - path: /shorten
            pathType: Exact
            backend:
              service:
                name: shortener-service
                port:
                  number: {{ .Values.config.shortenerServerPort }}
          - path: /[a-zA-Z0-9_-]{4,}
            pathType: ImplementationSpecific
            backend:
              service:
                name: resolver-service
                port:
                  number: {{ .Values.config.resolverServerPort }}
          - path: /
            pathType: Prefix
            backend:
              service:
                name: shortener-service
                port:
                  number: {{ .Values.config.shortenerServerPort }}
```

Note: unlike `nginx.conf` (which scoped CORS headers to only `/shorten`),
Ingress annotations apply to the whole `Ingress` object, so CORS is now
enabled for all three paths. This is an intentional simplification —
extra CORS response headers on the resolver's redirect response are
harmless (browsers ignore them on top-level navigations) and splitting
into two `Ingress` objects just to scope CORS narrower isn't worth the
added complexity for a local test deployment.

- [ ] **Step 5: Install `ingress-nginx` and drop the old gateway wait in `deploy.sh`**

Replace this line in `k8s/deploy.sh`:
```bash
kubectl rollout status deployment/api-gateway -n hopr --timeout=60s
```
and insert the `ingress-nginx` install **before** the `helm upgrade --install hopr ./k8s/hopr-chart` line (i.e., right after the Redis Cluster `helm upgrade --install hopr-redis ...` block):
```bash
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml

kubectl wait --namespace ingress-nginx \
  --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller \
  --timeout=180s
```

Full resulting `k8s/deploy.sh`:
```bash
#!/usr/bin/env bash
# k8s/deploy.sh
set -euo pipefail

cd "$(dirname "$0")/.."

if ! kind get clusters | grep -q '^hopr$'; then
  kind create cluster --config k8s/kind-config.yaml
fi

kubectl create namespace hopr --dry-run=client -o yaml | kubectl apply -f -

helm repo add bitnami https://charts.bitnami.com/bitnami >/dev/null 2>&1 || true
helm repo update

# Bitnami's free-tier images are amd64-only for mongodb, so MongoDB uses the
# plain official image instead (same as docker-compose.yml). Redis Cluster's
# Bitnami image is multi-arch and works fine.
helm upgrade --install hopr-redis bitnami/redis-cluster \
  --namespace hopr \
  --set cluster.nodes=6 \
  --set usePassword=false \
  --set image.repository=bitnamilegacy/redis-cluster \
  --set updateJob.image.repository=bitnamilegacy/kubectl \
  --set sysctlImage.repository=bitnamilegacy/os-shell \
  --set volumePermissions.image.repository=bitnamilegacy/os-shell \
  --set metrics.image.repository=bitnamilegacy/redis-exporter \
  --wait --timeout 5m

kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml

kubectl wait --namespace ingress-nginx \
  --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller \
  --timeout=180s

./k8s/build-and-load.sh

helm upgrade --install hopr ./k8s/hopr-chart --namespace hopr

kubectl rollout status deployment/config-server -n hopr --timeout=120s
kubectl rollout status deployment/keygen-service -n hopr --timeout=120s
kubectl rollout status deployment/shortener-service -n hopr --timeout=120s
kubectl rollout status deployment/resolver-service -n hopr --timeout=120s

echo "Hopr is up. Try: curl -X POST http://localhost:8888/shorten -d '{\"longUrl\":\"https://example.com\"}' -H 'Content-Type: application/json'"
```

- [ ] **Step 6: Recreate the cluster and verify end-to-end**

Run:
```bash
kind delete cluster --name hopr
./k8s/deploy.sh
```
Expected: script completes; `kubectl get pods -n ingress-nginx` shows the
controller pod `Running`; `kubectl get ingress -n hopr` shows
`hopr-ingress` with an assigned address.

```bash
curl -s -X POST http://localhost:8888/shorten -H 'Content-Type: application/json' -d '{"longUrl":"https://example.com"}'
```
Expected: `{"shortUrl":"http://localhost:8888/<KEY>"}`.

```bash
curl -sI http://localhost:8888/<KEY>
```
Expected: `HTTP/1.1 307` with `Location: https://example.com` — proves
the Ingress correctly routes the exact `/shorten` path to
`shortener-service` and the regex short-key path to `resolver-service`.

- [ ] **Step 7: Commit**

```bash
git add k8s/kind-config.yaml k8s/hopr-chart k8s/deploy.sh
git commit -m "feat: replace k8s nginx gateway with native Ingress"
```

---

### Task 10: Full end-to-end verification across all three deploy targets

**Files:**
- None created — verification only.

- [ ] **Step 1: Docker Compose, from a clean slate**

```bash
docker compose down -v
docker compose up --build -d
sleep 30
curl -s -X POST http://localhost/shorten -H 'Content-Type: application/json' -d '{"longUrl":"https://compose-check.example.com"}'
```
Expected: `{"shortUrl":"http://localhost/<KEY>"}`, then
`curl -sI http://localhost/<KEY>` returns `307` with the right `Location`.

```bash
docker compose down
```

- [ ] **Step 2: k8s, from a clean slate**

```bash
kind delete cluster --name hopr
./k8s/deploy.sh
curl -s -X POST http://localhost:8888/shorten -H 'Content-Type: application/json' -d '{"longUrl":"https://k8s-check.example.com"}'
```
Expected: `{"shortUrl":"http://localhost:8888/<KEY>"}`, then
`curl -sI http://localhost:8888/<KEY>` returns `307` with the right
`Location`.

- [ ] **Step 3: No commit** — this task is verification only.

---

### Task 11: Update documentation

**Files:**
- Modify: `k8s/README.md`
- Modify: `README.md`

**Interfaces:**
- None — documentation only.

- [ ] **Step 1: Update `k8s/README.md`'s architecture diagram and Eureka-specific notes**

Replace the "Architecture" section's cluster diagram (the block starting
`kind cluster "hopr"`) to remove the `eureka-server` line and the nginx
`api-gateway` line, adding `config-server` and noting the Ingress. Also
remove or rewrite the "Eureka server itself needs no app-level env vars"
paragraph in "Environment Variables — Mapping from `.env`" and the
"Networking Notes" bullet about Eureka propagation delay (no longer
applicable — there is no Eureka registry-fetch delay anymore since
service calls are direct DNS/Service lookups, not registry-based).

- [ ] **Step 2: Update root `README.md`'s architecture list**

In the "Architecture" section's numbered list, replace:
```
4. **Eureka Server**: Discovery service for registering and locating microservices.
```
with:
```
4. **Config Server**: Centralized configuration for all services (Spring Cloud Config, native/file-backed).
```

In "Technical Architecture", remove any Eureka-specific bullet if present
and ensure the k8s bullet mentions Ingress instead of a custom gateway pod
if it currently says otherwise.

- [ ] **Step 2: No test to run** — documentation-only change; skim-read for accuracy against the final state of `docker-compose.yml`, `k8s/deploy.sh`, and `k8s/hopr-chart` from Tasks 6-9.

- [ ] **Step 3: Commit**

```bash
git add k8s/README.md README.md
git commit -m "docs: update architecture docs for config-server and k8s Ingress"
```

---

## Self-Review Notes

- **Spec coverage:** ADR 0002 Decision 1 (remove Eureka) → Tasks 2-5, 6, 7, 8. Decision 2 (Config Server) → Task 1 plus the migration steps in Tasks 2-4, 6, 7, 8. Decision 3 (k8s Ingress) → Task 9. All three "Consequences" trade-offs (Config Server as hard dependency, `keygen.service.url` hostname coupling, `ingress-nginx` kind setup, two routing definitions to keep in sync) are called out inline where the relevant code is introduced, matching the ADR's own language.
- **Test-safety gap found during research, not in the original ADR:** `BaseIntegrationTest` in `shortener-service` doesn't set `@ActiveProfiles("test")`, so the `!test` config-import guard alone would not have protected it — fixed explicitly in Task 3, Step 8. This is exactly the kind of gap the ADR's "Test-safety" paragraph anticipated in principle but didn't enumerate by file; the plan closes it concretely.
- **Test-safety gap #2 found during research:** `shortener-service`'s `application-test.yml` excludes Eureka autoconfiguration classes by fully-qualified name; once the Eureka client dependency is removed (Task 5), those class references would throw at test context startup. Removed explicitly in Task 3, Step 9.
- **Type/interface consistency:** `KeyGenClient`'s constructor changes from `KeyGenClient(WebClient.Builder)` to `KeyGenClient(WebClient.Builder, String)` in Task 3 Step 5; the only other consumer, `KeyGenClientTest`, is updated in the same task (Step 6) to match. No other file constructs `KeyGenClient`.
- **Ordering dependency:** Task 5 (delete Eureka module + shared dependency) must run after Tasks 2-4 (remove all Eureka code from the three services) — if run first, the build breaks (services would still import `spring-cloud-netflix-eureka-client` classes no longer on the classpath). The plan's task order enforces this.
- **Placeholder scan:** no "TBD"/"TODO" strings; every YAML/Java snippet is complete, copy-pasteable content, not a description of intent.
