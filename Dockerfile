# build stage
FROM gradle:9.0-jdk21 AS build
WORKDIR /app

COPY build.gradle.kts settings.gradle.kts ./
RUN gradle build --no-daemon

COPY . .
RUN gradle build --no-daemon -x test

# actual running step
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=build /app/build/libs/*-all.jar app.jar
COPY src/main/resources/prod.conf ./
COPY src/main/resources/dev.conf ./

RUN addgroup -S appuser && adduser -S appuser -G appuser \
    && mkdir -p /app/run \
    && chown -R appuser:appuser /app
USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
CMD ["-config=dev.conf"]