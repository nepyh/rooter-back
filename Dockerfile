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

# IAM Roles Anywhere helper (AWS 외부 배포용 credential_process 용)
# 공식 배포본은 glibc 빌드(Amzn2023)라 musl 기반 alpine 에서는 gcompat 이 필요함
ARG RAH_VERSION=1.8.5
ARG TARGETARCH
RUN case "${TARGETARCH:-amd64}" in \
        arm64|aarch64) RAH_ARCH=Aarch64 ;; \
        *)             RAH_ARCH=X86_64 ;; \
    esac \
    && apk add --no-cache gcompat \
    && apk add --no-cache --virtual .download curl \
    && mkdir -p /opt/aws \
    && curl -fsSL "https://rolesanywhere.amazonaws.com/releases/${RAH_VERSION}/${RAH_ARCH}/Linux/Amzn2023/aws_signing_helper" \
         -o /opt/aws/aws_signing_helper \
    && chmod 755 /opt/aws/aws_signing_helper \
    && apk del .download \
    && /opt/aws/aws_signing_helper version

COPY --from=build /app/build/libs/*-all.jar app.jar
COPY src/main/resources/prod.conf ./
COPY src/main/resources/dev.conf ./
COPY src/main/resources/dev-s3.conf ./

# credential_process 프로파일 (AWS_PROFILE / AWS_CONFIG_FILE 로 참조됨)
COPY docker/aws/config /app/aws/config

RUN addgroup -S appuser && adduser -S appuser -G appuser \
    && mkdir -p /app/run \
    && chown -R appuser:appuser /app
USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
CMD ["-config=dev.conf"]