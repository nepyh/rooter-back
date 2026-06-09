# rooter-back

코털과 코틀린과 코인을쓰는 루터라는 서비스의 백엔드 프젝

성민규는 **running application using gradle (local jvm)** 섹션을 보라

# run

## running a production container

1. `.env.prod.example` 파일을 참고하여 `.env` 파일을 작성하거나 환경 변수를 설정합니다
> 프로덕션 설정을 로컬에서 테스트 해보고 싶은 경우, `.env.prod.example` 을 복사하여 파일의 이름을 `.env` 로 변경하고, 
> rootless 라면 (포트 관련 에러가 난다면) `SERVICE_PORT` 를 8080 같은 비 루트 포트로 변경해주세요

2. `docker compose -f ./docker-compose.prod.yml up -d --build` 또는 \
    `podman-compose -f ./docker-compose.prod.yml up -d --build` 를 실행합니다

3. `docker ps` 또는 `podman ps` 를 하여 대강 아래처럼 뜨면 성공
```
CONTAINER ID  IMAGE                                 COMMAND     CREATED         STATUS                   PORTS                   NAMES
10dfb1eac6c0  docker.io/library/postgres:15-alpine  postgres    21 seconds ago  Up 21 seconds (healthy)  5432/tcp                rooter-back-db
ef88babdc8c3  localhost/rooter-back_app:latest                  21 seconds ago  Up 11 seconds            0.0.0.0:3000->8080/tcp  rooter-back-app
```

## running a development container
1. `.env.dev.example` 복사하여 `.env` 로 이름만 바꿉니다

2. `docker compose -f ./docker-compose.dev.yml up -d --build` 또는 \
   `podman-compose -f ./docker-compose.dev.yml up -d --build` 를 실행합니다

3. `docker ps` 또는 `podman ps` 를 하여 대강 아래처럼 뜨면 성공
```
CONTAINER ID  IMAGE                                 COMMAND     CREATED         STATUS                   PORTS                   NAMES
10dfb1eac6c0  docker.io/library/postgres:15-alpine  postgres    21 seconds ago  Up 21 seconds (healthy)  5432/tcp                rooter-back-dev-db
ef88babdc8c3  localhost/rooter-back_app:latest                  21 seconds ago  Up 11 seconds            0.0.0.0:3000->8080/tcp  rooter-back-dev-app
```

> `docker-compose.dev.yml` 의 데이터베이스 컨테이너는 포트가 외부로 노출되어 있습니다.
> 접속해서 데이터 뜯어보기가 가능합니다

## running application using gradle (local jvm)
애플리케이션을 컨테이너가 아닌, 로컬에서 바로 실행하는 방식입니다

1. `.env.dev.example` 복사하여 `.env` 로 이름만 바꿉니다

2. `docker compose -f ./docker-compose.dev.yml up -d --build db` 또는 \
   `podman-compose -f ./docker-compose.dev.yml up -d --build db` 를 실행합니다

3. `docker ps` 또는 `podman ps` 를 하여 대강 아래처럼 떠야함 (`rooter-back-dev-app` 이 없어야함)
```
CONTAINER ID  IMAGE                                 COMMAND     CREATED         STATUS                   PORTS     NAMES
10dfb1eac6c0  docker.io/library/postgres:15-alpine  postgres    21 seconds ago  Up 21 seconds (healthy)  5432/tcp  rooter-back-dev-db
```

4. `./gradlew run --args="-config=dev.conf"`
