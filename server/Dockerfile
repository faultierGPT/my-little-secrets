# Multi-stage: build a self-contained app image with the JRE only (no Gradle/JDK at runtime).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
# installDist (from the `application` plugin) produces a runnable dist: bin/server + lib/*.jar
#
# `--configure-on-demand` restricts configuration to :server and its transitive project
# dependencies (:core). This keeps the Docker build from trying to configure :desktop
# (a JavaFX client that doesn't belong in a server image and whose directory is stripped
# by .dockerignore) or :android (not in the build context). On a normal local JVM build
# the flag is a no-op because :desktop and :android are configured when their directories
# exist; here it just avoids spurious configuration-time failures in the container.
RUN chmod +x gradlew && ./gradlew :server:installDist --no-daemon --configure-on-demand

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN useradd -r -u 10001 mls
COPY --from=build /src/server/build/install/server /app
ENV MLS_PORT=8080
EXPOSE 8080
USER mls
ENTRYPOINT ["/app/bin/server"]
