FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
COPY reflect-config.json .
RUN mvn package -DskipTests -q

FROM ghcr.io/graalvm/native-image-community:21 AS native
WORKDIR /app
COPY --from=builder /app/target/rinha.jar .
COPY --from=builder /app/target/dependency ./dependency
COPY reflect-config.json .
RUN native-image \
  -jar rinha.jar \
  -cp ".:dependency/*" \
  --no-fallback \
  -H:Name=rinha-runner \
  -H:ReflectionConfigurationFiles=reflect-config.json

FROM debian:bookworm-slim
WORKDIR /app
COPY --from=native /app/rinha-runner .
EXPOSE 9999
ENTRYPOINT ["./rinha-runner"]
