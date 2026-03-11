# ============================================================
# Stage 1: Build
# ============================================================
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy dependency descriptors first for Docker layer caching
COPY pom.xml .
COPY k8s-batch-app/pom.xml k8s-batch-app/
COPY k8s-batch-integration-tests/pom.xml k8s-batch-integration-tests/
COPY k8s-batch-e2e-tests/pom.xml k8s-batch-e2e-tests/
RUN mvn dependency:go-offline -B -pl k8s-batch-app -am

# Copy source and build
COPY k8s-batch-app/src k8s-batch-app/src
RUN mvn package -DskipTests -B -pl k8s-batch-app -am && \
    java -Djarmode=tools -jar k8s-batch-app/target/*-exec.jar extract --layers --launcher --destination extracted

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

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", \
            "org.springframework.boot.loader.launch.JarLauncher"]
