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
        reg.add("spring.mongodb.uri", mongo::getConnectionString);
    }
}