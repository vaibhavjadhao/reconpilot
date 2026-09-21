# ---------------------------------------------------------------- build ----
# A multi-stage build: this stage has Maven, the JDK, the whole dependency
# cache and the source code -- roughly 700 MB of things an attacker would
# enjoy finding on a running server. None of it reaches the final image.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# pom.xml alone first, so the dependency download is cached as its own layer.
# Docker rebuilds a layer only when its inputs change; copying the source at
# the same time would re-download every dependency on every source edit.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests need Docker (Testcontainers) and run in CI, not inside an image build.
RUN mvn -B -q clean package -DskipTests

# -------------------------------------------------------------- runtime ----
# JRE, not JDK: no compiler, no build tools, a fraction of the size.
FROM eclipse-temurin:21-jre-alpine AS runtime

# A container process running as root IS root on the host kernel if it escapes.
# Nothing here needs privilege, so nothing here gets it.
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app

# --chown on the COPY, not a chown afterwards.
#
# `chown -R` REWRITES every file it touches, and in Docker's copy-on-write
# layers a rewritten file is stored again in a new layer. Doing it after the
# COPY stored the 109 MB jar twice and added 114 MB to the image for no reason.
# Setting ownership during the copy writes it correctly the first time.
COPY --from=build --chown=app:app /build/target/*.jar app.jar

# Staged uploads land here. mkdir alone creates nothing large, so this layer
# stays tiny.
RUN mkdir -p /app/staging && chown app:app /app/staging
USER app

EXPOSE 8080

# MaxRAMPercentage, not a fixed -Xmx. Modern JVMs read the CONTAINER's memory
# limit rather than the host's, so this scales automatically when the limit
# changes. A hardcoded heap either wastes the difference or gets OOM-killed.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"

# Containers must fail loudly. Restarting a JVM limping along after an
# OutOfMemoryError is better than leaving it half-working and undetectable.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
