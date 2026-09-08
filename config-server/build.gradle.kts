dependencies {
    implementation("org.springframework.cloud:spring-cloud-config-server")
}

tasks.named<Jar>("jar") {
    enabled = false
}
