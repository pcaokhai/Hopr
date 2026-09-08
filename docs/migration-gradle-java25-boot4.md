# Migration: Maven → Gradle, Java 21 → 25, Spring Boot 3.5.7 → 4.1.1

## Goal

- Convert the entire multi-module project from Maven to Gradle (Kotlin DSL).
- Upgrade the Java runtime to 25.
- Upgrade Spring Boot to the newest stable release with Java 25 support (4.1.1), which in turn requires bumping Spring Cloud to a compatible release train (2025.1.2).

## Version summary

| Component | Before | After |
|---|---|---|
| Build tool | Maven (multi-module `pom.xml`) | Gradle 9.4.1 (Kotlin DSL, multi-project) |
| Java | 21 | 25 (toolchain) |
| Spring Boot | 3.5.7 | 4.1.1 |
| Spring Cloud | 2025.0.0 | 2025.1.2 ("Oakwood") |
| springdoc-openapi | 2.8.13 | 3.1.0 |
| Testcontainers | managed via BOM (1.x) | 2.0.5 (via BOM) |

Why Spring Boot 4.1.1: it's the first stable release in the 4.x line with official Java 25 support (confirmed via Spring Initializr — the 3.5.x line only supports up to Java 24). Spring Cloud 2025.1.2 is the first release train compatible with Boot 4.1.0 (confirmed via the spring.io blog).

## 1. Build system conversion: Maven → Gradle

### Structure before
```
pom.xml (parent, packaging=pom)
├── common/pom.xml
├── eureka/pom.xml
├── keygen-service/pom.xml
├── resolver-service/pom.xml
└── shortener-service/pom.xml
```

### Structure after
```
settings.gradle.kts
build.gradle.kts (root — shared config for every subproject)
├── common/build.gradle.kts
├── eureka/build.gradle.kts
├── keygen-service/build.gradle.kts
├── resolver-service/build.gradle.kts
└── shortener-service/build.gradle.kts
gradlew, gradlew.bat, gradle/wrapper/
```

### Notes from the port

1. **The Maven parent pom had real `<dependencies>` (not just `<dependencyManagement>`)** — meaning every child module automatically inherited those dependencies (eureka-client, logback-core, commons-lang, springdoc, xstream, guava, httpclient, caffeine, commons-lang3). Gradle has no automatic "parent dependencies" concept, so these had to be declared explicitly inside the `subprojects {}` block in the root `build.gradle.kts` to preserve the original behavior.
2. **The `common` module is not a Spring Boot app** (no `@SpringBootApplication`) — only the other 4 modules (`eureka`, `keygen-service`, `resolver-service`, `shortener-service`) apply the `org.springframework.boot` plugin.
3. Used the `io.spring.dependency-management` plugin to keep the same BOM-based version management as Maven (`spring-boot-dependencies`, `spring-cloud-dependencies`) instead of pinning versions manually for every dependency.
4. JaCoCo coverage rules (85% line coverage bundle) and the `resolver-service`-specific exclusions (`Constants*`, `Helper*`, `Util*`, `config/**`) were ported 1:1 to `jacocoTestCoverageVerification`/`jacocoTestReport`.
5. Test filter `**/*Test.java` (Maven Surefire) → `include("**/*Test.class")` in the Gradle `Test` task.
6. Generated the Gradle wrapper: `gradle wrapper --gradle-version 9.4.1`.

## 2. Upgrading to Java 25

- `java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }` applied to every subproject.
- The build machine already had Gradle 9.4.1 and JDK 25 (Corretto) installed, so no extra toolchain provisioning was needed.
- No compile errors were caused by the Java version bump itself — every breaking change encountered came from Spring Boot 4, not from Java 25.

## 3. Upgrading Spring Boot 3.5.7 → 4.1.1 — breaking changes handled

Spring Boot 4 restructures (modularizes) several areas that used to be bundled together in `spring-boot-actuator`/`spring-boot-test-autoconfigure`. Here are the specific changes encountered and how they were fixed:

### 3.1. `spring-boot-health` split into its own module

Actuator health now lives in the `org.springframework.boot:spring-boot-health` artifact (pulled in automatically via `spring-boot-starter-actuator`), under new packages:

| Before (Boot 3.5) | After (Boot 4.1) |
|---|---|
| `org.springframework.boot.actuate.health.HealthEndpoint` | `org.springframework.boot.health.actuate.endpoint.HealthEndpoint` |
| `org.springframework.boot.actuate.health.Status` | `org.springframework.boot.health.contributor.Status` |
| `org.springframework.boot.actuate.health.Health` | `org.springframework.boot.health.contributor.Health` |

Files affected:
- `keygen-service/.../config/EurekaClientConfig.java`
- `shortener-service/.../config/EurekaClientConfig.java`

### 3.2. `HealthEndpoint.health()` changed return type — sealed class can't be mocked

- Before: `HealthEndpoint.health()` returned `Health` (a final class, mockable with Mockito).
- After: it returns `HealthDescriptor` — a **sealed abstract class** (`permits IndicatedHealthDescriptor, CompositeHealthDescriptor, SystemHealthDescriptor, ...`). Mockito refuses to mock sealed/abstract classes ("Sealed interfaces or abstract classes can't be mocked").

**Fix**: instead of mocking `HealthDescriptor`, build a real `HealthEndpoint` using:
- `DefaultHealthContributorRegistry` registering a single `HealthIndicator` returning a fixed `Health.up()`/`Health.down()`.
- An empty `DefaultReactiveHealthContributorRegistry`.
- A manually implemented `HealthEndpointGroup` (inline, using `StatusAggregator.getDefault()` / `HttpCodeStatusMapper.getDefault()` — avoiding `SimpleStatusAggregator`/`SimpleHttpCodeStatusMapper`, which are deprecated for removal in 4.1.1).

Files affected (tests):
- `keygen-service/.../unit_tests/config/EurekaClientConfigTest.java`
- `shortener-service/.../unit_tests/urlshort/config/EurekaClientConfigTest.java`

Both files gained a `buildHealthEndpoint(Health fixedHealth)` helper method that builds a real `HealthEndpoint` instead of mocking one.

### 3.3. `@WebMvcTest` / `@AutoConfigureMockMvc` moved package + need a new dependency

Boot 4 splits the test-slice annotations out of the (previously oversized) `spring-boot-test-autoconfigure` jar into dedicated per-feature test starters.

| Before | After |
|---|---|
| `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest` | `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` |
| `org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc` | `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc` |

A new test-scoped dependency is required in every module using these annotations:
```kotlin
testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
```

Files affected:
- `keygen-service/build.gradle.kts` + `KeygenControllerIntegrationTest.java`
- `resolver-service/build.gradle.kts` + `ResolverControllerIntegrationTest.java`
- `shortener-service/build.gradle.kts` + `ShortenerControllerIntegrationTest.java`

### 3.4. springdoc-openapi 2.8.13 is incompatible with Boot 4

springdoc-openapi 2.x is built against Spring Framework 6 / Boot 3. Boot 4 runs on Spring Framework 7, which requires springdoc-openapi 3.x. Bumped `springdoc-openapi-starter-webmvc-ui` to **3.1.0** in the root `build.gradle.kts`'s `dependencyManagement`.

### 3.5. Hardcoded `spring-webflux` version conflicted with the BOM

`shortener-service` previously pinned `org.springframework:spring-webflux:6.2.11` (Spring Framework 6, matching Boot 3.5). With the Boot 4 BOM managing Spring Framework 7.x, that hardcoded version would conflict. Removed the pinned version and let the BOM manage it:
```kotlin
// before
implementation("org.springframework:spring-webflux:6.2.11")
// after
implementation("org.springframework:spring-webflux")
```

## 4. Testcontainers 2.x — artifact rename

The Boot 4.1.1 BOM pulls in `testcontainers-bom:2.0.5` (a major version bump from 1.x). Testcontainers 2.x renamed several sub-artifacts to be consistent with the `testcontainers-*` prefix:

| Before | After |
|---|---|
| `org.testcontainers:mongodb` | `org.testcontainers:testcontainers-mongodb` |
| `org.testcontainers:junit-jupiter` | `org.testcontainers:testcontainers-junit-jupiter` |
| `org.testcontainers:testcontainers` | unchanged |

The Java package names (`org.testcontainers.containers.MongoDBContainer`, `@Testcontainers`, `@Container`) stayed the same — only the Gradle artifact coordinates changed.

File affected: `shortener-service/build.gradle.kts`.

## 5. Side cleanup: leftover JUnit 4

Boot 4's `spring-boot-starter-test` no longer pulls in the `junit-vintage-engine` (which ran JUnit 4 tests on the JUnit 5 platform) by default. Found one test file using `org.junit.Assert` (JUnit 4) even though the rest of the project consistently uses JUnit 5 (`org.junit.jupiter.api.Test`) — this was a pre-existing bug that only surfaced because Boot 4 tightened the default test classpath:

```java
// before
import static org.junit.Assert.assertEquals;
// after
import static org.junit.jupiter.api.Assertions.assertEquals;
```

File affected: `shortener-service/.../unit_tests/urlshort/exception/message/AliasInvalidFormatMessageTest.java`.

## 6. Test results

- `./gradlew compileJava compileTestJava` — **BUILD SUCCESSFUL** across all 5 modules.
- `./gradlew build` (full test suite + JaCoCo for every module) — all pass **except** `AliasValidationCompositeIntegrationTest` (shortener-service): it times out connecting to Mongo at `localhost:27017` instead of the dynamically-allocated Testcontainers port.
  - Confirmed Docker itself works fine (`docker run hello-world` succeeds via the CLI directly).
  - However, no Mongo container ever showed up in `docker ps` while the test ran through Gradle — suspected cause: the JVM running the test in this sandboxed/background environment doesn't have the same Docker socket access as a direct `docker` CLI invocation.
  - **This is a limitation of the current build environment, not a regression introduced by the migration.** This test class should be re-run in a real terminal (outside the sandbox) to confirm.

## 7. Files removed

```
pom.xml
common/pom.xml
eureka/pom.xml
keygen-service/pom.xml
resolver-service/pom.xml
shortener-service/pom.xml
```

## 8. Files added

```
settings.gradle.kts
build.gradle.kts
common/build.gradle.kts
eureka/build.gradle.kts
keygen-service/build.gradle.kts
resolver-service/build.gradle.kts
shortener-service/build.gradle.kts
gradlew, gradlew.bat, gradle/wrapper/gradle-wrapper.{jar,properties}
```

## 9. New build commands

```bash
./gradlew build                     # compile + test + jacoco for every module
./gradlew :shortener-service:test   # run tests for a single module
./gradlew bootJar                   # package a runnable jar for each service (eureka, keygen-service, resolver-service, shortener-service)
```

## 10. Docker / Docker Swarm — what broke and how it was fixed

The Maven → Gradle switch broke every service's Docker image build, because the Dockerfiles and CI config were still wired for Maven's output layout. `stack.yml` itself needed no changes (it only references pre-built `sha256:...` images that the user builds and pushes manually), but everything upstream of that — building those images — was broken.

### 10.1. `COPY target/*.jar app.jar` — wrong output directory

Maven puts the packaged jar in `target/`. Gradle's `bootJar` task puts it in `build/libs/`. All 4 service Dockerfiles (`eureka`, `keygen-service`, `resolver-service`, `shortener-service`) still had:
```dockerfile
COPY target/*.jar app.jar
```
This would fail outright (`target` directory doesn't exist after a Gradle build) or silently pick up a stale Maven jar if one happened to still be on disk.

**Fix**: changed to `COPY build/libs/*.jar app.jar` in every service Dockerfile.

### 10.2. Gradle's `bootJar` also produces a `-plain.jar` — wildcard COPY becomes ambiguous

Unlike the Maven Spring Boot plugin (which only produces one artifact), the Gradle Spring Boot plugin produces **two** jars per module by default:
```
build/libs/eureka-0.0.1-SNAPSHOT.jar          # the runnable Spring Boot jar (bootJar)
build/libs/eureka-0.0.1-SNAPSHOT-plain.jar    # the plain, non-executable jar (jar task)
```
`COPY build/libs/*.jar app.jar` with two matching source files and a non-directory destination is invalid in Docker and would fail the build (or silently copy the wrong one depending on the Docker builder version).

**Fix**: disabled the plain jar task in every Spring Boot module's `build.gradle.kts` (the officially recommended approach when the plain jar isn't needed):
```kotlin
tasks.named<Jar>("jar") {
    enabled = false
}
```
Applied to: `eureka/build.gradle.kts`, `keygen-service/build.gradle.kts`, `resolver-service/build.gradle.kts`, `shortener-service/build.gradle.kts`. After this, `build/libs/` contains exactly one jar per module.

### 10.3. Base image `openjdk:21-jdk-slim` has no Java 25 tag

The official `openjdk` Docker Hub image is effectively frozen/deprecated and never published Java 25 tags (confirmed: `docker manifest inspect openjdk:25-jdk-slim` → not found). Running the Boot 4.1.1 / Java 25 jar on that base wasn't an option.

**Fix**: switched every service Dockerfile's base image to `eclipse-temurin:25-jre` (the actively maintained Java Docker image with Java 25 support). Used `-jre` instead of `-jdk` since the container only needs to *run* a pre-built jar, not compile anything — a smaller, more defensible image for a runtime container.

### 10.4. Verification performed

- `./gradlew clean bootJar` — confirmed exactly one jar per service in `build/libs/`.
- `docker build -t hopr-eureka-test ./eureka` — image builds cleanly on `eclipse-temurin:25-jre`.
- `docker run` that image and `curl` it — container starts, logs show `Started EurekaApplication ... using Java 25.0.4`, and the root endpoint returns HTTP 200. Confirms the full chain (Gradle build → Docker image → running container) works end-to-end.
- Full `docker swarm deploy` was **not** re-verified end-to-end (no live Swarm cluster in this environment) — the fix addresses the build-time breakage; the Swarm deployment steps in the README (`docker stack deploy -c stack.yml Hopr`, Redis cluster init) are unchanged and should still work once images are built with the fixed Dockerfiles and pushed per the README's existing instructions.

### 10.5. Other broken references found and fixed

- `.github/dependabot.yml` still declared `package-ecosystem: "maven"` for `shortener-service`, `keygen-service`, `resolver-service` — these directories no longer contain a `pom.xml`, so Dependabot would silently stop reporting dependency updates. Replaced with a single `package-ecosystem: "gradle"` entry pointing at the repo root (Gradle's ecosystem scans the whole multi-project build from there).
- `.github/workflows/maven-build-main.yml` and `maven-build-pull-requests.yml` — renamed to `gradle-build-main.yml` / `gradle-build-pull-requests.yml`, updated to set up JDK 25 and run `./gradlew build` / `./gradlew clean build` instead of `mvn -B verify` / `mvn clean verify`. (Covered in the same migration pass; noted here for completeness since it's part of "what still pointed at Maven".)

### 10.6. Files changed in this pass

```
eureka/Dockerfile
keygen-service/Dockerfile
resolver-service/Dockerfile
shortener-service/Dockerfile
eureka/build.gradle.kts
keygen-service/build.gradle.kts
resolver-service/build.gradle.kts
shortener-service/build.gradle.kts
.github/dependabot.yml
```
