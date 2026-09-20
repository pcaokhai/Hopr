dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-data-cassandra")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-micrometer-tracing-brave")
    implementation("io.micrometer:micrometer-tracing-bridge-brave")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.cloud:spring-cloud-starter-config")
    implementation("org.springframework.retry:spring-retry")
    implementation("org.springframework.kafka:spring-kafka")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.testcontainers:testcontainers-cassandra")
    testImplementation("org.testcontainers:kafka:1.21.4")
    testImplementation("org.springframework.kafka:spring-kafka-test")
}

tasks.named<Test>("test") {
    include("**/*Test.class")
}

tasks.named<Jar>("jar") {
    enabled = false
}
