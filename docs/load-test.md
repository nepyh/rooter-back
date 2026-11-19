# 부하 테스트 (nGrinder)

로컬에서 nGrinder 로 API 에 부하를 주고, nGrinder(클라이언트 쪽 TPS·응답 시간)와 Sentry 성능 추적(서버 쪽 트랜잭션·쿼리 span, `docs/sentry.md` 6절)을 함께 본다.
**운영 서버·운영 DB 에는 부하를 주지 않는다.** dev 서버가 없으므로 모든 측정은 로컬에서 한다.

파일은 `loadtest/` 에 있다.

| 파일 | 내용 |
| --- | --- |
| `seed.sql` | 학생 계정 300명 + 학생 프로필(학교·학년·반)·플랜보드·할일·캘린더 일정. **로컬 DB 전용** (아래 1번) |
| `docker-compose.ngrinder.yml` | nGrinder controller(웹 UI `http://localhost:8300`, admin/admin) + agent |
| `home-scenario.groovy` | 로그인 후 홈 화면이 부르는 GET 6개를 반복 |

## 1. 시드 데이터

```bash
docker compose -f docker-compose.dev.yml up -d db
set -a; . ./.env; set +a
docker exec -i rooter-back-dev-db psql -U "$DB_USER" -d "$DB_NAME" -v local=1 -v users=300 < loadtest/seed.sql
```

- 계정은 `loadtest1@rooter.test` ~ `loadtest300@rooter.test`, 비밀번호 `loadtest1234!` 이다. 다시 실행하면 기존 `loadtest%` 계정을 지우고 새로 만든다.
- 과목·출판사·교과서 같은 **마스터 데이터에 테스트용 행을 넣는다.** 운영 DB 에서 실행되면 안 되므로 두 가지 장치가 있다.
  - `-v local=1` 을 직접 붙이지 않으면 아무것도 하지 않고 멈춘다.
  - 접속이 컨테이너 안 소켓이나 localhost 가 아니면 멈춘다. 그래서 `docker exec` 로 실행한다.
  - 한계: SSH 터널 등으로 원격 DB 를 localhost 에 포워딩하면 두 번째 검사는 통과한다. 그때는 `-v local=1` 만 남으므로, 터널을 연 채로 시드를 실행하지 않는다.
- 할일은 모두 **23:00 이후**에 잡혀 있다. 이유는 아래 "함정" 참고. 테스트는 시드한 날 23:00 전에 돌리고, 날짜가 바뀌면 다시 시드한다.

## 2. 앱 실행

부하 테스트용으로 띄울 때는 `.env` 에서 이렇게 둔다.

- `SENTRY_TRACES_SAMPLE_RATE=1` (끝나면 `0` 으로 되돌린다)
- `LLM_API_KEY` 는 비운다 — 스케줄러가 실제 LLM 을 부르지 않게

ECS 운영 스펙(1 vCPU)과 스레드 수를 맞추려면 JVM 에 `-XX:ActiveProcessorCount=1` 을 준다.
Ktor(Netty) 는 CPU 수로 스레드 수를 정하므로, 이 옵션이 없으면 개발 머신의 코어 수로 측정하게 된다.

```bash
./gradlew shadowJar
java -XX:ActiveProcessorCount=1 -Xmx512m -jar build/libs/rooter-back-1.0-SNAPSHOT-all.jar -config=dev.conf
```

측정 전에 30~60초 정도 요청을 흘려 JIT 를 데운다.
데우지 않으면 처음 1~2분 구간의 숫자가 나쁘게 나와서, 비교할 때 앞뒤 구간이 공정하지 않다.

## 3. nGrinder

```bash
docker compose -f loadtest/docker-compose.ngrinder.yml up -d controller
```

**에이전트는 컨테이너보다 로컬(JDK 11)로 띄우는 것을 권장한다** (함정 1·2 참고).

```bash
# 컨트롤러가 뜬 뒤 (웹 UI 가 열리면)
curl -L -o ngrinder-agent.tar "http://localhost:8300/agent/download"
tar xf ngrinder-agent.tar && cd ngrinder-agent
# 스크립트가 PATH 의 java 를 쓰므로 PATH 도 11 로 맞춘다 (brew install openjdk@11)
export JAVA_HOME=/opt/homebrew/opt/openjdk@11 PATH=/opt/homebrew/opt/openjdk@11/bin:$PATH
./run_agent.sh
```

웹 UI 에서 할 일은 이렇다.

1. Script 메뉴에서 새 Groovy 스크립트를 만들고 `home-scenario.groovy` 내용을 붙여 넣는다.
2. Performance Test 를 만든다.
   - 스크립트 파라미터(`param`)에 앱 주소를 넣는다. 로컬 에이전트면 `http://localhost:8080` 이다.
   - VU 를 단계적으로 늘린다. 측정에 쓴 값은 아래와 같다.
     - processes 2 × threads 100 = 200 VU
     - Ramp-Up 은 thread 기준으로 10 → 200, 10씩, 15초 간격
     - 6분 실행
3. 결과 화면의 TPS·Mean Test Time 그래프를 보고, Sentry Explore → Traces 에서 같은 시간대를 함께 본다.

## 4. 측정 결과 (#361, 2026-11-18)

로컬, `-XX:ActiveProcessorCount=1`, 45초 데운 뒤 10 → 200 VU 6분, 로그인 + GET 6개 시나리오.

| | 평균 TPS | 최고 TPS | 평균 응답 | 처리한 테스트 | 오류 |
| --- | --- | --- | --- | --- | --- |
| 수정 전 (nGrinder 테스트 70) | 219.6 | 396 | 757.4ms | 71,064 | 0 |
| 수정 후 (nGrinder 테스트 71) | 689.7 | 1,613 | 230.1ms | 240,465 | 0 |

- **발견한 문제**
  - 20 VU 부터 TPS 가 더 오르지 않고, 응답 시간만 VU 수에 비례해 늘었다.
  - DB 커넥션 10개 중 9개가 놀았다.
- **원인**
  - Netty call 스레드는 CPU 수만큼 만들어져 1 vCPU 에서는 1개뿐이다.
  - 그런데 Exposed `transaction {}` 과 JWT 검증의 사용자 조회가 그 스레드에서 DB 를 블로킹 호출했다.
  - 그래서 요청이 한 줄로 섰다. 스레드 덤프에서 call 스레드가 1~2개뿐인 것으로 확인했다.
- **수정**: `BlockingCallDispatcher.kt` 가 요청 처리를 `Dispatchers.IO` 로 옮긴다. 수정 후 TPS 는 3.1배, 평균 응답은 −70% 다.
- **Sentry 에서 본 것**
  - 수정 전 트랜잭션은 5ms 대로 빨랐다. 줄 서는 시간이 파이프라인에 들어오기 전에 생겨서 **서버 모니터링에는 보이지 않았다.**
  - 그래서 클라이언트 쪽 부하 테스트를 함께 봐야 했다.
  - 수정 후에는 느린 요청의 DB 쿼리 사이에 빈 구간이 보인다. 커넥션 풀을 기다리는 시간으로 추정한다.
- **다음 병목**: `DB_MAX_POOL_SIZE`(10).

Sentry 의 트랜잭션 개수는 샘플링 추정치이고, 부하가 크면 SDK 가 일부를 버린다.
그래서 처리량은 nGrinder 숫자로 보고, Sentry 는 응답 시간 분포와 트랜잭션 안의 구간을 보는 데 쓴다.

## 함정

1. **에이전트는 JDK 11 이어야 한다.**
   - nGrinder 3.5.9 의 Groovy 3.0.5 는 Java 17 이상의 클래스 파일을 읽지 못한다.
   - 그래서 `Unsupported class file major version 61` 오류로 스크립트가 실패한다.
2. **Docker Desktop(macOS)을 거치면 연결이 끊긴다.**
   - 동시 연결이 많아지면 포트 포워딩이 `ConnectionClosedException: Connection closed by peer` 를 낸다.
   - 앱 문제처럼 보이지만, 같은 조건을 로컬로 돌리면 오류가 0이다.
   - Apple Silicon 에서 amd64 에이전트 이미지는 에뮬레이션이라 느려서 `STOP_BY_ERROR` 로 끝나기도 한다.
3. **시드 할일 시각과 스케줄러.**
   - 끝난 할일은 `task_quiz_trigger` 가 실제 LLM 으로 퀴즈를 만든다.
   - 할일이 테스트 시간대에 끝나면 측정 중에 LLM 호출이 섞인다. 실제로 측정 한 번이 이것 때문에 무효가 됐다.
   - 그래서 시드 할일은 23:00 이후에 두고, `LLM_API_KEY` 도 비운다.
4. **`HTTPRequest` 는 `@BeforeProcess` 에서 한 번 만들어야 한다.**
   - 그러지 않으면 `registerDataLogExpression() is not supported from worker threads` 오류가 난다.
5. **에이전트 컨테이너가 계속 죽는 경우.**
   - 컨트롤러가 준비되기 전에 에이전트가 파일을 받으면 0바이트 파일이 남는다.
   - `docker compose -f loadtest/docker-compose.ngrinder.yml rm -sf agent` 로 지우고 다시 띄운다.
   - 에이전트를 직접 받을 때도 `curl -L` 이 필요하다. 302 리다이렉트를 따라가지 않으면 0바이트 파일이 생긴다.
6. **로컬 DB 비밀번호가 `.env` 와 다른 경우.**
   - DB 컨테이너는 볼륨을 처음 만들 때의 비밀번호를 계속 쓴다.
   - 그래서 `.env` 를 나중에 바꾸면 앱이 `password authentication failed` 로 뜨지 않는다.
