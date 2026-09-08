plugins {
    java
    id("org.springframework.boot") version "4.1.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("jacoco")
}

val bootApps = setOf("shortener-service", "resolver-service", "keygen-service", "config-server")

allprojects {
    group = "com.pcaokhai"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "jacoco")

    if (name in bootApps) {
        apply(plugin = "org.springframework.boot")
    } else {
        // library module: no executable jar
        tasks.named("jar") {
            enabled = true
        }
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.add("-parameters")
        options.encoding = "UTF-8"
    }

    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.1")
            mavenBom("org.springframework.cloud:spring-cloud-dependencies:2025.1.2")
        }
        dependencies {
            dependency("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0")
            dependency("org.apache.commons:commons-compress:1.28.0")
        }
    }

    dependencies {
        "implementation"("ch.qos.logback:logback-core:1.6.3")
        "implementation"("commons-lang:commons-lang:20030203.000129")
        "implementation"("org.springdoc:springdoc-openapi-starter-webmvc-ui")
        "implementation"("com.thoughtworks.xstream:xstream:1.4.21")
        "implementation"("com.google.guava:guava:33.7.1-android")
        "implementation"("org.apache.httpcomponents:httpclient:4.5.14")
        "implementation"("com.github.ben-manes.caffeine:caffeine")
        "implementation"("org.apache.commons:commons-lang3:3.20.0")
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        violationRules {
            rule {
                element = "BUNDLE"
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = "0.85".toBigDecimal()
                }
            }
        }
    }
}
