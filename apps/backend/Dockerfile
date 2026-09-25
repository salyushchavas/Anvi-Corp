# syntax=docker/dockerfile:1

# ── Build stage ───────────────────────────────────────────────────────
# Java 17 to match <java.version>17</java.version> in pom.xml.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /build

# Copy the Maven wrapper + POM first and resolve dependencies in their own
# layer. Docker caches this layer, so editing source code doesn't re-download
# the entire dependency tree on every rebuild — the difference between a
# ~20-second rebuild and a ~5-minute one.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src src
# Tests are skipped HERE on purpose. They run via `./mvnw clean package`
# during development (383 of them, several minutes); re-running the whole
# suite on every image build would make `docker compose up --build` painful
# for someone who just wants to see the app. The image build is a packaging
# step, not a test gate.
RUN ./mvnw -B -q -DskipTests package

# ── Runtime stage ─────────────────────────────────────────────────────
# JRE only — no compiler, no Maven, no source. Smaller image, smaller
# attack surface.
FROM eclipse-temurin:17-jre
WORKDIR /app

# Run as a non-root user. If a bug ever lets someone execute code in this
# container, it should not be running as root.
RUN useradd --create-home --shell /usr/sbin/nologin appuser
COPY --from=build /build/target/anvicorp-api-*.jar app.jar

# Local-file storage for uploaded documents, resumes and generated PDFs.
# When S3 credentials are absent (the default for local development), the
# app writes here instead — see DocumentVaultService. Declared as a VOLUME
# so uploads survive container restarts.
RUN mkdir -p /app/uploads && chown -R appuser:appuser /app
VOLUME ["/app/uploads"]

USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
