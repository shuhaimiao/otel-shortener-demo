# Stage 1: Build the application using Maven
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /workspace/app

COPY .mvn/ .mvn
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -B

COPY src ./src

RUN ./mvnw package -DskipTests -B

# Stage 2: Create the runtime image
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Copy the JAR file from the builder stage
ARG JAR_FILE=/workspace/app/target/*.jar
COPY --from=builder ${JAR_FILE} application.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "/app/application.jar"]
