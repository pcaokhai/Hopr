FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*
VOLUME /tmp
COPY build/libs/*.jar app.jar
# The JVM only reads the jar, so root ownership of /app.jar is fine; what matters is
# that the process itself is unprivileged, so a container breakout cannot land as host root.
RUN useradd -r -u 1001 appuser
USER appuser
ENTRYPOINT ["java", "-jar", "/app.jar"]
