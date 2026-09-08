# config-server/Dockerfile
FROM eclipse-temurin:25-jre
VOLUME /tmp
COPY build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
