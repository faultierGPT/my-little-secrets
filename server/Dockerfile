# Multi-stage: build a self-contained app image with the JRE only (no Gradle/JDK at runtime).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
# installDist (from the `application` plugin) produces a runnable dist: bin/server + lib/*.jar
RUN chmod +x gradlew && ./gradlew :server:installDist --no-daemon

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN useradd -r -u 10001 mls
COPY --from=build /src/server/build/install/server /app
ENV MLS_PORT=8080
EXPOSE 8080
USER mls
ENTRYPOINT ["/app/bin/server"]
