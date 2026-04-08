# build stage
FROM gradle:8.5-jdk21 AS build
WORKDIR /app

COPY build.gradle.kts settings.gradle.kts ./
RUN gradle build --no-daemon > /dev/null 2>&1 || true

COPY . .
RUN gradle build --no-daemon -x test

# actual running step
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

RUN addgroup -S appuser && adduser -S appuser  -G appuser
USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]