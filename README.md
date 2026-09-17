# rooter-back

코털과 코틀린과 코인을쓰는 루터라는 서비스의 백엔드 프젝

성민규는 **running application using gradle (local jvm)** 섹션을 보라

서버가 잘 켜졌는지가 궁금하다면 그냥 `http://localhost:8080/api/health` 을 접속하라

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
- `~Table` 은 여러 테이블 조인·집계나 `insertIgnore` 처럼 DAO 로 표현할 수 없는 연산에서만 직접 사용한다.
  (`PlanTaskTable innerJoin DailyPlanTable innerJoin PlanBoardTable` — 소유자 확인/집계 조회,
  `JobRunTable.insertIgnore { ... }` — ON CONFLICT DO NOTHING 중복 방지 claim)
- FK 를 id 로만 쓰는 테이블은 참조 엔티티 로드가 없도록 `EntityID` 속성으로 노출한다.
  (`var subjectId by Table.subjectId` — `.value` 로 id 접근)

# run

실행 환경은 4가지이고, 스토리지·자격증명 방식이 각각 다릅니다.

| 실행 환경 | 설정 파일 | 스토리지 | 자격증명 획득 | 필요한 자원 |
|---|---|---|---|---|
| 로컬 JVM (개발) | `dev.conf` / `dev-s3.conf` | local / S3 | `~/.aws/config` 의 `credential_process` | helper 바이너리, 인증서 |
| 로컬 컨테이너 (개발) | `dev.conf` / `dev-s3.conf` | local / S3 | helper 가 띄운 가짜 IMDS | `./certs` 인증서 |
| AWS 외부 배포 (프러덕션) | `prod.conf` | S3 고정 | 이미지에 임베드된 helper (`credential_process`) | `./certs` 인증서 |
| AWS 내부 배포 (ECS) | `prod.conf` | S3 고정 | **ECS task role** | task role 의 S3 권한 |

공통 사항
- S3 를 쓸 때는 `AWS_REGION` / `AWS_BUCKET` 이 필요하며, 두 값의 리전이 버킷 리전과 같아야 합니다 (다르면 S3 가 301 로 거부)
- 버킷은 비공개이며, 파일 접근은 presigned URL 로 합니다
- 정적 액세스 키(`AWS_ACCESS_KEY_ID` 등)는 어느 환경에서도 `.env`/태스크 정의에 넣지 않습니다 — 기본 체인에서 env 가 최우선이라 다른 인증 경로가 조용히 무시됩니다
- s3 는 설정이 대체로 복잡함. [여기](https://app.notion.com/p/aws-s3-3957e62f83ed80608cd8e2abab084758?source=copy_link)참고

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

### AWS 외부(비 ECS) 프러덕션 — 스토리지·인증

`prod.conf` 는 스토리지가 **S3 로 고정**되어 있어 `AWS_REGION` / `AWS_BUCKET` 이 없으면 기동에 실패합니다(HOCON 치환 오류).

S3 자격증명은 이미지에 포함된 `aws_signing_helper` 를 `credential_process` 로 호출해 얻습니다 (사이드카 컨테이너·IMDS 포트 불필요).

1. `.env` 를 채웁니다
```
AWS_REGION=<버킷 리전>
AWS_BUCKET=<버킷명>
ROLESANYWHERE_TRUST_ANCHOR_ARN=arn:aws:rolesanywhere:<리전>:<계정>:trust-anchor/<UUID>
ROLESANYWHERE_PROFILE_ARN=arn:aws:rolesanywhere:<리전>:<계정>:profile/<UUID>
ROLESANYWHERE_ROLE_ARN=arn:aws:iam::<계정>:role/<역할명>
```

2. `./certs/` 에 `client-cert.pem` 과 `client-key.pem` 을 둡니다
> Trust Anchor 에 등록한 CA 가 발급한 인증서여야 하고, `keyUsage = critical,digitalSignature` 가 있어야 합니다.
> 이 디렉터리는 `.gitignore` 로 제외되어 있습니다 (개인키 커밋 금지)

인증 흐름
```
컨테이너 기동
  └ SDK 기본 체인 → Profile 프로바이더(AWS_PROFILE=rooter-prod, AWS_CONFIG_FILE=/app/aws/config)
      └ credential_process → /opt/aws/aws_signing_helper credential-process
          └ IAM Roles Anywhere CreateSession (클라이언트 인증서로 서명)
              └ 임시 자격증명 → S3 요청
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

### S3 로 띄우기 (선택)

기본값은 로컬 파일 스토리지입니다(`APP_CONFIG=dev.conf`, `run/store` 디렉터리 사용). S3 로 띄우려면:

1. `./certs/` 에 클라이언트 인증서(`client-cert.pem` / `client-key.pem`)를 둡니다
2. `.env` 에 아래를 설정합니다
```
APP_CONFIG=dev-s3.conf
AWS_REGION=<버킷 리전>
AWS_BUCKET=<버킷명>
ROLESANYWHERE_TRUST_ANCHOR_ARN=...
ROLESANYWHERE_PROFILE_ARN=...
ROLESANYWHERE_ROLE_ARN=...
```
3. helper 프로파일을 켜서 기동합니다
```bash
docker compose -f ./docker-compose.dev.yml --profile roles-anywhere up -d --build
```

`APP_CONFIG` 는 **compose 에서만** 읽히는 값이라, 로컬 jvm 실행에는 영향을 주지 않습니다.

인증 흐름
```
roles-anywhere-helper (인증서 + ARN 3종, 가짜 EC2 IMDS :9911)
  └ app 컨테이너(AWS_EC2_METADATA_SERVICE_ENDPOINT=http://roles-anywhere-helper:9911)
      └ SDK IMDS 프로바이더 → 임시 자격증명 → S3 요청
```

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

### S3 로 실행하기 (선택)

- `-config=dev.conf` → 로컬 파일 스토리지 (`run/store`)
- `-config=dev-s3.conf` → S3 (`.env` 에 `AWS_REGION` / `AWS_BUCKET` 이 없으면 기동 실패)

로컬 JVM 은 `~/.aws/config` 의 프로파일에서 자격증명을 얻습니다 (helper 를 직접 호출하는 설정).

```ini
# ~/.aws/config
[profile rooter-s3-dev]
credential_process = /opt/homebrew/bin/aws_signing_helper credential-process --certificate /path/to/client-cert.pem --private-key /path/to/client-key.pem --trust-anchor-arn <TA_ARN> --profile-arn <PROFILE_ARN> --role-arn <ROLE_ARN>
region = <버킷 리전>
```

```bash
AWS_PROFILE=rooter-s3-dev ./gradlew run --args="-config=dev-s3.conf"
```

> default 가 아닌 프로파일은 반드시 `[profile 이름]` 형식으로 써야 합니다 (`[이름]` 은 프로파일로 인식되지 않습니다).
> `credential_process` 값은 한 줄로 쓰고, 경로는 절대경로를 권장합니다.

인증 흐름
```
./gradlew run -config=dev-s3.conf
  └ SDK 기본 체인 → Profile 프로바이더(AWS_PROFILE)
      └ credential_process → aws_signing_helper → Roles Anywhere CreateSession
          └ 임시 자격증명 → S3 요청
```

## deploying to AWS (ECS)

`.github/workflows/deploy.yml` 이 `main` 브랜치 push 시 이미지를 ECR 에 올리고 ECS 서비스를 갱신합니다.

1. ECS **태스크 정의**에 `AWS_REGION` / `AWS_BUCKET` 환경변수를 넣습니다 (`prod.conf` 의 치환값)
2. **태스크 역할(task role)** 에 버킷 접근 권한을 부여합니다
   - `s3:GetObject` / `s3:PutObject` / `s3:DeleteObject` → `arn:aws:s3:::<버킷>/*`
   - `s3:ListBucket` → `arn:aws:s3:::<버킷>`
3. 정적 키(`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`)는 태스크 정의에 **넣지 않습니다**

인증 흐름
```
ECS 태스크 시작
  └ SDK 기본 체인 → ECS 컨테이너 자격증명 엔드포인트
      └ 태스크 역할 → 임시 자격증명 → S3 요청
```

> AWS 내부 배포는 IAM Roles Anywhere·클라이언트 인증서·helper 가 **전혀 필요 없습니다** (task role 로 해결).
