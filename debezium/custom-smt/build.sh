#!/bin/bash

# Build the custom SMT JAR
echo "Building custom Kafka Connect SMT for trace context propagation..."

# Build using Docker to ensure consistent environment
docker build -t custom-smt-builder .

# Create target directory if it doesn't exist
mkdir -p target

# Extract the JAR from the builder image
docker run --rm -v "$(pwd)/target:/output" custom-smt-builder \
  sh -c "cp /build/target/kafka-connect-trace-smt-*.jar /output/"

echo "Custom SMT JAR built successfully!"
echo "JAR location: target/kafka-connect-trace-smt-1.0.0.jar"