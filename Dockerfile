FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B -q
COPY src ./src
RUN mvn package -DskipTests -B -q
COPY resources/ /resources/
RUN java -Xmx2g -cp target/rinha.jar:target/dependency/* \
      dev.marcelovitor.rinha.store.DataPreprocessor \
      /resources/references.json.gz \
      /data/

FROM ghcr.io/graalvm/native-image-community:25-muslib AS native
WORKDIR /app
COPY --from=builder /app/target/rinha.jar .
COPY --from=builder /app/target/dependency ./dependency
RUN native-image \
      --no-fallback \
      --static --libc=musl \
      -O3 \
      -H:Name=rinha-runner \
      -jar rinha.jar \
      -cp ".:dependency/*"

FROM scratch
COPY --from=native /app/rinha-runner /rinha-runner
COPY --from=builder /data/ /data/
EXPOSE 9999
ENTRYPOINT ["/rinha-runner", "-Xmx140m"]
