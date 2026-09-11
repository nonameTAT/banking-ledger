# syntax=docker/dockerfile:1

# Maven 3.9.16 and Temurin 25 match .mvn/wrapper/maven-wrapper.properties and the
# java.version property, so an image build and a local ./mvnw build use the same
# toolchain.
FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /workspace

# Resolve dependencies in their own layer so editing sources does not refetch them.
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src/ src/
# Tests are skipped here because they start Testcontainers, which needs a Docker
# daemon. CI runs them in a separate job before this image is built.
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

RUN groupadd --system banking && useradd --system --gid banking banking

COPY --from=build /workspace/target/banking-ledger-*.jar app.jar
USER banking:banking

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
