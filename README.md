# rooter-back

코털과 코틀린과 코인을쓰는 루터라는 서비스의 백엔드 프젝

성민규는 **running application using gradle (local jvm)** 섹션을 보라

서버가 잘 켜졌는지가 궁금하다면 그냥 `http://localhost:8080/api/health` 을 접속하라

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
# 개발 컨벤션

## ORM 테이블 정의

프로젝트 전역에서 테이블 정의는 아래 패턴 하나로 통일한다.

- 하나의 테이블을 정의하는 코틀린 파일은 `~Table` 형태의 이름을 사용한다. (예: `UserTable.kt`, `JobRunTable.kt`)
- 파일 내에는 파일 이름과 동일한 이름의 테이블 정의 클래스가 존재한다.
- 테이블 정의 클래스는 `~IdTable` 사용을 지향한다. PK 는 `~IdTable` 이 자동으로 설정한다.
- 테이블 정의 클래스와 쌍을 이루는 `~Row` 클래스가 같은 파일에 존재한다.
- 외부 파일에서 ORM 을 사용할 때는 `~Row` 클래스를 기본으로 사용하고, `~Table` 클래스는 필요할 때만 사용한다.

```kotlin
// UserTable.kt — 테이블 정의 클래스(~Table) 와 엔티티(~Row) 가 같은 파일에 존재한다.
object UserTable : IntIdTable("users") {
    val email = varchar("email", 320).uniqueIndex()
    val username = varchar("username", 12)
    val createdAt = timestampWithTimeZone("created_at")
}

class UserRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<UserRow>(UserTable)

    var email by UserTable.email
    var username by UserTable.username
    var createdAt by UserTable.createdAt
}
```

- 외부 파일에서는 `UserRow.find { UserTable.email eq ... }`, `UserRow.new { ... }` 처럼 `~Row` 를 기본으로 사용한다.
- `~Table` 은 `insertIgnore` 처럼 DAO 로 표현할 수 없는 연산에서만 직접 사용한다.
  (`JobRunTable.insertIgnore { ... }` — ON CONFLICT DO NOTHING 중복 방지 claim)
