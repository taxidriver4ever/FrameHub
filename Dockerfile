FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY .mvn/docker-settings.xml /tmp/maven-settings.xml
COPY user-service ./user-service
COPY gateway-service ./gateway-service
COPY video-asset-service ./video-asset-service
RUN --mount=type=cache,target=/root/.m2 mvn -s /tmp/maven-settings.xml -B -ntp package -DskipTests
FROM eclipse-temurin:21-jre AS base
WORKDIR /app
USER 10001
ENTRYPOINT ["java","-jar","/app/app.jar"]
FROM base AS runtime
ARG SERVICE
COPY --from=build /build/${SERVICE}/target/${SERVICE}-1.0.0-SNAPSHOT.jar app.jar
FROM base AS video-dependencies
USER root
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg fonts-dejavu-core && rm -rf /var/lib/apt/lists/* && mkdir -p /work && chown 10001:10001 /work
USER 10001
FROM video-dependencies AS video-runtime
COPY --from=build /build/video-asset-service/target/video-asset-service-1.0.0-SNAPSHOT.jar app.jar
