# Build the distribution with the same Gradle and JDK the CI job uses, then copy only the runtime into a JRE image.
FROM gradle:8.9-jdk17 AS build
WORKDIR /src
COPY . .
RUN gradle installDist --no-daemon -x test

FROM eclipse-temurin:17-jre
RUN groupadd --system routinepilot && useradd --system --gid routinepilot --uid 10001 routinepilot
WORKDIR /app
COPY --from=build /src/build/install/routinepilot-backend /app
USER 10001:10001
# 8080 serves HTTP. Set RP_SSL_PORT and mount a keystore to serve HTTPS directly, or terminate TLS at a proxy.
EXPOSE 8080
ENTRYPOINT ["/app/bin/routinepilot-backend"]
