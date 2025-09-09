FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# Copy POM and source files
COPY pom.xml .
COPY src ./src

# Build the custom SMT JAR
RUN mvn clean package

# The JAR will be available at /build/target/kafka-connect-trace-smt-1.0.0.jar