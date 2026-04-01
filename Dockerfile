# ============================================================
# Stage 1: Build
# ============================================================
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy dependency descriptors first for Docker layer caching.
# All sibling module POMs must be present even though only k8s-batch-jobs is built —
# Maven resolves the full reactor from the parent POM and fails if any <module> is missing.
COPY pom.xml .
COPY k8s-batch-rules-kie/pom.xml k8s-batch-rules-kie/
COPY k8s-batch-jobs/pom.xml k8s-batch-jobs/
COPY k8s-batch-integration-tests/pom.xml k8s-batch-integration-tests/
COPY k8s-batch-e2e-tests/pom.xml k8s-batch-e2e-tests/
COPY k8s-batch-api-gateway/pom.xml k8s-batch-api-gateway/
COPY k8s-batch-api-gateway-tests/pom.xml k8s-batch-api-gateway-tests/
COPY k8s-batch-crud/pom.xml k8s-batch-crud/
COPY k8s-batch-crud-tests/pom.xml k8s-batch-crud-tests/
RUN mvn dependency:go-offline -B -pl k8s-batch-jobs -am

# Copy license scripts (needed for Maven validate phase) and source
COPY scripts/license scripts/license
COPY k8s-batch-rules-kie/src k8s-batch-rules-kie/src
COPY k8s-batch-jobs/src k8s-batch-jobs/src
# skip.checkstyle: config/checkstyle/suppressions.xml is not in the Docker context —
# checkstyle is a CI/dev validation tool, not needed for producing the runtime JAR
RUN mvn package -DskipTests -Dskip.checkstyle=true -B -pl k8s-batch-jobs -am && \
    # Spring Boot layertools: splits JAR into layers ordered by change frequency for Docker cache optimization
    java -Djarmode=tools -jar k8s-batch-jobs/target/*-exec.jar extract --layers --launcher --destination extracted

# ============================================================
# Stage 2: Runtime
# ============================================================
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy layers in order of change frequency (least to most)
COPY --from=build /app/extracted/dependencies/ ./
COPY --from=build /app/extracted/spring-boot-loader/ ./
COPY --from=build /app/extracted/snapshot-dependencies/ ./
COPY --from=build /app/extracted/application/ ./

RUN chown -R appuser:appgroup /app
USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --retries=3 --start-period=60s \
  CMD ["wget", "--no-verbose", "--tries=1", "--spider", "http://localhost:8080/actuator/health"]

# JarLauncher is required when the JAR is extracted into layers (a regular java -jar uses the embedded launcher)
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", \
            "org.springframework.boot.loader.launch.JarLauncher"]
