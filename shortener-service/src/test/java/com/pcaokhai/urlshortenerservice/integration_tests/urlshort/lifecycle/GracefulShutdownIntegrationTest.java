package com.pcaokhai.urlshortenerservice.integration_tests.urlshort.lifecycle;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.Properties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the shutdown settings we ship in config-repo/application.yml and in config-server's
 * own application.yml
 * ({@code server.shutdown=graceful} + {@code spring.lifecycle.timeout-per-shutdown-phase})
 * actually let an in-flight request finish instead of dropping it.
 *
 * <p>The two properties are read out of the shipped config-repo/application.yml rather than
 * hardcoded here, so deleting them from the shipped file fails this test.
 *
 * <p>ponytail: this boots a three-autoconfiguration throwaway web app rather than the real
 * shortener context. The behaviour under test is Boot's web-server lifecycle driven by those
 * two properties, which is identical in any Boot app; using the real context would only add a
 * Scylla container and a shorten round trip to the same assertion. Upgrade path: if a service
 * ever registers its own SmartLifecycle in the web-server shutdown phase, test that service's
 * real context instead.
 */
class GracefulShutdownIntegrationTest {

    @SpringBootConfiguration
    @ImportAutoConfiguration({
            TomcatServletWebServerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class
    })
    @RestController
    static class SlowApp {

        static volatile CountDownLatch requestStarted = new CountDownLatch(1);

        @GetMapping("/slow")
        String slow() throws InterruptedException {
            requestStarted.countDown();
            Thread.sleep(2_000);
            return "finished";
        }
    }

    private static Properties shippedConfig(String relativePath) {
        Path repoRoot = Path.of("").toAbsolutePath();
        while (!repoRoot.resolve("config-server").toFile().isDirectory()) {
            repoRoot = repoRoot.getParent();
        }
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(
                repoRoot.resolve(relativePath)));
        yaml.afterPropertiesSet();
        return yaml.getObject();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "config-server/src/main/resources/config-repo/application.yml",
            "config-server/src/main/resources/application.yml"
    })
    void completesAnInFlightRequestAfterShutdownBegins(String shippedConfigPath) throws Exception {
        SlowApp.requestStarted = new CountDownLatch(1);
        Properties shipped = shippedConfig(shippedConfigPath);
        assertThat(shipped.getProperty("server.shutdown")).isEqualTo("graceful");
        String shutdownTimeout = shipped.getProperty("spring.lifecycle.timeout-per-shutdown-phase");
        assertThat(shutdownTimeout).isNotNull();

        ConfigurableApplicationContext context = new SpringApplicationBuilder(SlowApp.class)
                .properties(
                        // This throwaway context must not inherit the module's test config,
                        // which points spring.config.import at the real config-server.
                        "spring.config.location=",
                        "spring.cloud.config.enabled=false",
                        "server.port=0",
                        "server.shutdown=" + shipped.getProperty("server.shutdown"),
                        "spring.lifecycle.timeout-per-shutdown-phase=" + shutdownTimeout)
                .run();
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/slow"))
                .timeout(Duration.ofSeconds(30))
                .build();
        CompletableFuture<HttpResponse<String>> inFlight =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString());

        // Shut down only once the request is genuinely being served, otherwise the test could
        // pass by racing ahead of the server ever accepting it.
        assertThat(SlowApp.requestStarted.await(10, TimeUnit.SECONDS)).isTrue();
        context.close();

        HttpResponse<String> response = inFlight.get(30, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("finished");
    }
}
