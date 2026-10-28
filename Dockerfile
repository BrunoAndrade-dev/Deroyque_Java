# syntax=docker/dockerfile:1
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
COPY docker/Healthcheck.java /healthcheck/Healthcheck.java
# Optional build secrets support corporate/cloud proxies without storing their
# settings or CA in an image layer. The temporary trust store lives in tmpfs.
RUN --mount=type=secret,id=proxy_ca \
    --mount=type=secret,id=maven_settings \
    --mount=type=tmpfs,target=/build-trust \
    --mount=type=cache,target=/root/.m2 \
    set -eu; \
    if [ -f /run/secrets/proxy_ca ]; then \
      cp "$JAVA_HOME/lib/security/cacerts" /build-trust/cacerts; \
      keytool -importcert -noprompt -alias build-proxy \
        -file /run/secrets/proxy_ca -keystore /build-trust/cacerts -storepass changeit; \
      export MAVEN_OPTS="-Djavax.net.ssl.trustStore=/build-trust/cacerts -Djavax.net.ssl.trustStorePassword=changeit"; \
    fi; \
    if [ -f /run/secrets/maven_settings ]; then \
      mvn -B -ntp -s /run/secrets/maven_settings -DskipTests package; \
    else \
      mvn -B -ntp -DskipTests package; \
    fi; \
    mkdir -p /artifacts/healthcheck; \
    cp target/deroyque-test-0.0.1-SNAPSHOT.jar /artifacts/application.jar; \
    cp target/deroyque-test-0.0.1-SNAPSHOT-provider-mock.jar /artifacts/provider-mock.jar; \
    cp /root/.m2/repository/org/wiremock/wiremock-standalone/3.13.1/wiremock-standalone-3.13.1.jar /artifacts/wiremock.jar; \
    javac -d /artifacts/healthcheck /healthcheck/Healthcheck.java

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
COPY --from=build --chown=10001:10001 /artifacts/healthcheck /opt/healthcheck
USER 10001:10001

FROM runtime AS provider-mock
COPY --from=build --chown=10001:10001 /artifacts/provider-mock.jar /artifacts/wiremock.jar /app/
COPY --chown=10001:10001 mock-provider /app/mock-provider
EXPOSE 8081
HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=5 \
  CMD ["java", "-cp", "/opt/healthcheck", "Healthcheck", "http://127.0.0.1:8081/__admin/health"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-cp", "/app/wiremock.jar:/app/provider-mock.jar", "wiremock.Run", "--port", "8081", "--root-dir", "/app/mock-provider", "--extensions", "br.com.deroyque.activation.support.IdempotentProviderMock", "--disable-request-logging", "--disable-banner"]

FROM runtime AS application
COPY --from=build --chown=10001:10001 /artifacts/application.jar /app/application.jar
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=5 \
  CMD ["java", "-cp", "/opt/healthcheck", "Healthcheck", "http://127.0.0.1:8080/actuator/health/readiness"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/application.jar"]
