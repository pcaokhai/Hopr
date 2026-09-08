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

tasks.named<Test>("test") {
    include("**/*Test.class")
}

tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<JacocoReport>("jacocoTestReport") {
    classDirectories.setFrom(
        classDirectories.files.map {
            fileTree(it) {
                exclude("**/Constants*.class", "**/Helper*.class", "**/Util*.class", "**/config/**")
            }
        }
    )
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    classDirectories.setFrom(
        classDirectories.files.map {
            fileTree(it) {
                exclude("**/Constants*.class", "**/Helper*.class", "**/Util*.class", "**/config/**")
            }
        }
    )
}
