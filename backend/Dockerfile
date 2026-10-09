FROM eclipse-temurin:21-jre
# Take Ubuntu security fixes (e.g. OpenSSL) as soon as they ship, not when the base image is rebuilt.
RUN apt-get update && apt-get upgrade -y --no-install-recommends && rm -rf /var/lib/apt/lists/*
# The base image ships Canonical's pebble outside dpkg, so apt never patches it; nothing here runs it.
RUN rm -f /usr/bin/pebble
WORKDIR /app
RUN groupadd --gid 10001 analyzer && useradd --uid 10001 --gid analyzer --no-create-home analyzer \
    && mkdir -p /app/var/document-objects && chown -R analyzer:analyzer /app
COPY --chown=analyzer:analyzer target/ai-deal-analyzer-0.1.0-SNAPSHOT.jar /app/application.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/application.jar"]
