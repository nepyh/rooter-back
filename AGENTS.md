# rooter-back

중학생용 AI 시험공부 계획 앱 **루터(rooter)** 의 백엔드입니다.
Kotlin + Ktor + Koin + Exposed(PostgreSQL) 조합을 사용합니다.

## 먼저 읽을 것

- `README.md` — 실행 방법 (컨테이너 3가지, 로컬 JVM)
- `.github/CONTRIBUTING.md` — 이슈/PR/브랜치/커밋 컨벤션. **이 문서의 규칙이 우선합니다.**

## 실행과 확인

```bash
# 로컬 JVM 으로 실행 (DB 컨테이너만 띄우고 앱은 로컬)
docker compose -f ./docker-compose.dev.yml up -d --build db
./gradlew run --args="-config=dev.conf"

# 살아있는지 확인
curl http://localhost:8080/api/health
```

- `.env` 파일이 없으면 `run` 태스크가 경고만 내고 그냥 뜹니다. 환경 변수 누락으로 이상하게 죽으면 `.env` 부터 확인하세요.
- 테스트는 **로컬 PostgreSQL 이 필요합니다.** `planboard_test` DB 는 테스트가 직접 만듭니다.
  접속 정보는 `TEST_JDBC_URL` / `TEST_DB_USER` / `TEST_DB_PASSWORD` 로 덮어쓸 수 있습니다.

```bash
./gradlew test
```

## 용어 — module 과 unit

- **module ≈ domain.** 하나의 서비스 단위입니다. `domain` 이 아니라 `module` 이라 부르는 이유는
  Koin 이 컴포넌트 묶음을 module 이라 부르기 때문입니다.
- **unit ≈ `~Service`, `~Repo`.** 하나의 module 안에서 실제 동작을 구성하는 세부 컴포넌트입니다.

`src/main/kotlin/com/github/nepyh/rooter/` 아래 구조는 이렇습니다.

```
common/          여러 module 이 함께 쓰는 것 (ApiRoute, ErrorResponse, config, database, auth)
module/
  AppModule.kt   모든 module 을 묶고 StatusPages 를 설치하는 최상단
  user/          유저·인증
  planboard/     플랜보드 (하나의 공부 계획)
  scheduler/     파생 스케줄링 + job_runs 실행 로그
  school/        NICE 교육정보 API 래퍼
  storage/       파일 저장 (local 구현체)
  health/        헬스체크
```

## 새 module 을 추가하는 순서

기존 `planboard` module 이 가장 최신 패턴입니다. 새로 만들 때는 이 순서를 따르세요.

1. `module/<name>/model/XxxTable.kt` — Exposed 테이블 + Row 클래스를 **쌍으로** 정의
2. `module/<name>/dto/XxxDto.kt` — `@Serializable` 요청·응답 DTO
3. `module/<name>/exception/XxxException.kt` — `sealed class` + `status`/`code` 를 담은 예외
4. `module/<name>/XxxService.kt` — 비즈니스 로직. 검증은 여기서, DB 접근은 `transaction { }` 안에서
5. `module/<name>/api/XxxApi.kt` — `ApiRoute` 를 반환하는 함수. 라우팅 + OpenAPI 설명
6. `module/<name>/XxxModule.kt` — Koin `module { }`. Api 는 `single(named("xxxApi"))` 로 등록
7. `module/AppModule.kt` — `includes(XxxModule())` 추가 + StatusPages 에 예외 매핑 추가

7번을 빼먹으면 **컴파일은 되지만 API 가 아예 안 붙습니다.** 가장 흔한 실수입니다.

## 지켜야 하는 패턴

### ORM — `~Table` + `~Row` 쌍

`IntIdTable` 로 테이블을, `IntEntity` 로 Row 를 정의하고 같은 파일에 둡니다.

```kotlin
object PlanBoardTable : IntIdTable("plan_boards") {
    val userId = reference("user_id", UserTable)
    val title = varchar("title", 100)
}

class PlanBoardRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<PlanBoardRow>(PlanBoardTable)
    var user by UserRow referencedOn PlanBoardTable.userId
    var title by PlanBoardTable.title
}
```

### 에러 응답 계약

모든 에러는 `ErrorResponse(code, message)` 형태로 나갑니다. 프론트엔드가 `code` 로 분기하므로
**`code` 문자열은 계약입니다. 마음대로 바꾸면 앱이 깨집니다.**

예외는 `sealed class` 로 만들고 `status`·`code`·메시지를 예외 자신이 들고 있게 합니다.
그리고 `AppModule.kt` 의 `StatusPages` 에 매핑합니다. **API 핸들러 안에서 try-catch 하지 않습니다.**

```kotlin
sealed class PlanBoardValidationException(
    val status: HttpStatusCode, val code: String, message: String
) : Exception(message) {
    class InvalidTitleException : PlanBoardValidationException(
        HttpStatusCode.BadRequest, "INVALID_TITLE", "제목은 1~100자여야 합니다."
    )
}
```

### 인증

JWT Bearer 방식입니다. 보호가 필요한 라우트는 `authenticate("auth-jwt") { }` 로 감싸고,
사용자 식별은 `principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()` 로 꺼냅니다.
**남의 데이터를 건드리지 못하게 소유권 검증을 Service 에서 반드시 하세요.** (`PlanBoardForbiddenException`)

### OpenAPI 문서

`.describe { }` 로 tag·summary·응답 코드를 적습니다. 특히 400 응답에는
어떤 `code` 가 나갈 수 있는지 적어주세요. 프론트엔드가 이것을 보고 작업합니다.
개발 모드에서 Swagger UI 가 함께 뜹니다.

### 시각 값

시각 컬럼은 `timestampWithTimeZone`, 코드에서는 `OffsetDateTime` 을 씁니다.

## DDL 은 이 저장소에 없습니다

실제 스키마는 **`nepyh/rooter-ddl` 저장소**가 정본입니다.
여기 Exposed 테이블 정의는 Repo 계층 구현에 필요한 만큼만 표현한 것입니다.

**컬럼이나 제약을 바꾸면 `rooter-ddl` 에도 반영해야 합니다.** 한쪽만 바꾸면
로컬 테스트는 통과하는데 배포하면 터집니다. (실제로 `daily_plans` 유니크 제약에서 겪었습니다.)

## 협업 규칙

`.github/CONTRIBUTING.md` 가 정본이고, 요약하면 이렇습니다.

- 이슈: `feature:` / `problem:`
- PR: `add:` / `edit:` / `fix:` — 본문 끝에 `Closes #이슈번호`
- 브랜치: `feature/` / `fix/` / `rm/` / `refactor/`
- 커밋: `add:` `rm:` `edit:` `fix:` `refactor:` `format:`
  커밋 메시지만 보고 **어느 파일의 어디가 어떻게 바뀌었는지** 알 수 있게 씁니다.
  `format:` 커밋에서는 코드 내용을 절대 바꾸지 않습니다.

### PR 은 작게, 그리고 머지될 때까지 기다립니다

- 새 브랜치는 **항상 `main` 에서 자릅니다.** 리뷰를 기다리는 브랜치 위에 다음 작업을 쌓으면,
  PR 이 서로 물려서 어느 것도 머지할 수 없게 됩니다.
- 리뷰가 늦으면 브랜치를 쌓지 말고 **리뷰를 재촉하세요.** 그게 훨씬 빠릅니다.

## 시크릿

API 키·비밀번호·토큰을 커밋하지 않습니다. `openocode.json` 은 로컬 전용입니다.
커밋 전에 `git diff` 로 확인하세요. **push 하면 히스토리에 영구히 남습니다.**
