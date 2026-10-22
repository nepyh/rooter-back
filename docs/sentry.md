# 서버 오류 모니터링 설정

관련 이슈: [#313](https://github.com/nepyh/rooter-back/issues/313)

`SENTRY_DSN`이 비어 있으면 오류 수집을 하지 않습니다. 기존 서버 실행 방법은 그대로 사용할 수 있습니다.
JVM SDK를 사용하며 HTTP 5xx 예외, SDK 초기화 이후 기동 실패, 스케줄러 실패를 수집합니다.
4xx 검증·인증 오류와 코루틴 취소는 제외합니다. Logback 자동 수집, 성능 추적, 프로파일링은 사용하지 않습니다.

## 1. Sentry 프로젝트 준비

1. Sentry에서 서버용 Java 프로젝트를 만듭니다. 앱 프로젝트와 분리하면 서버 장애를 구분하기 쉽습니다.
2. 프로젝트 설정의 **Client Keys (DSN)** 에서 DSN을 복사합니다.
3. 프로젝트에 접근할 팀원을 초대하고 새 오류 알림을 설정합니다.

DSN은 이벤트를 보낼 프로젝트 주소이며, 관리·업로드 권한을 가진 인증 토큰과 다릅니다.
실제 DSN 값은 코드에 고정하지 않고 환경변수로 주입합니다. Sentry 인증 토큰은 이 구성에 필요하지 않습니다.

## 2. 로컬 또는 Docker Compose 설정

실행 환경에 맞는 `.env.dev.example` 또는 `.env.prod.example`을 참고하여 로컬 `.env`에 다음 값을 넣습니다.

```dotenv
SENTRY_DSN=<서버 프로젝트의 DSN>
SENTRY_ENVIRONMENT=development
SENTRY_RELEASE=local-check
```

`SENTRY_ENVIRONMENT`는 `development`, `staging`, `production`처럼 환경을 구분하는 이름입니다.
`SENTRY_RELEASE`에는 배포한 커밋 SHA나 고정된 버전명을 넣습니다. 두 값에 사용자 정보나 토큰을 넣지 않습니다.
두 값은 영문·숫자와 `_.@/+:-`로 구성된 1~200자만 허용합니다.

기존 README의 로컬 JVM 또는 Docker Compose 실행 절차로 서버를 재시작합니다.
세 설정 파일(`dev.conf`, `dev-s3.conf`, `prod.conf`) 모두 Sentry 변수를 선택 항목으로 읽습니다.
잘못된 DSN이면 서버는 계속 실행하고 Sentry만 비활성화합니다. 이때 로그에는 DSN을 출력하지 않습니다.

## 3. ECS 배포 설정

1. GitHub 저장소 **Settings → Secrets and variables → Actions** 에 `SENTRY_DSN` secret을 등록합니다.
2. 기존 배포 workflow를 실행합니다. workflow가 ECS 태스크 정의의 앱 컨테이너에 DSN, `production`, 배포 커밋 SHA를 주입합니다.
3. 배포 뒤 Sentry에서 `environment:production`과 해당 release를 선택합니다.

secret이 없으면 오류 수집을 하지 않습니다. 이 변경을 머지하거나 배포하기 전에 DSN을 등록할 필요는 없습니다.
ECS 태스크 정의를 읽을 수 있는 운영자는 주입된 환경변수도 볼 수 있으므로 해당 권한은 기존 운영자에게만 유지합니다.

## 4. 실제 프로젝트에서 한 번 확인하기

자동 테스트는 메모리 transport를 사용하므로 외부 Sentry에 요청을 보내지 않습니다. 실제 전송 확인은 별도로 수행합니다.

1. 개발용 DSN과 `development` 환경으로 로컬 서버를 시작합니다.
2. IntelliJ에서 `Bootstrapper.kt`의 `ErrorMonitoring.initialize(...)` 다음 줄에 중단점을 걸고 디버그 실행합니다.
3. **Evaluate Expression**에서 아래 코드를 한 번 실행합니다. 이름·이메일·토큰·실제 오류 메시지는 넣지 않습니다.

```kotlin
com.github.nepyh.rooter.common.monitoring.ErrorMonitoring.capture(
    IllegalStateException("sentry-development-check"),
    com.github.nepyh.rooter.common.monitoring.ErrorSource.STARTUP
)
io.sentry.Sentry.flush(2000)
```

4. Sentry에서 이벤트가 한 건 도착했는지 확인합니다. 예외 타입, 스택, environment, release, `error.source`가 보여야 합니다.
5. 이벤트 JSON에 원문 메시지, 사용자, 이메일, URL, 요청 본문, headers, 쿠키, DB 쿼리가 없는지 확인합니다.
6. 중단점을 제거하고 정상 실행합니다. 테스트 오류를 만드는 공개 API는 추가하지 않습니다.

이 절차를 수행하기 전에는 실제 프로젝트 연결을 검증했다고 판단하지 않습니다.
설정 파일 해석 등 SDK 초기화 이전에 발생한 오류는 Sentry로 수집할 수 없으므로 기존 서버 로그를 확인합니다.

## 수집 정보와 로컬 검증

전송하는 정보는 예외 클래스, 패키지, 파일명·함수명·줄 번호, 발생 시각, 환경·릴리스, 오류 발생 경계입니다.
예외 메시지와 중첩 원인은 그대로 전송하지 않으며, 모든 예외 메시지를 `[exception message removed]`로 바꿉니다.
SDK가 scope를 병합한 뒤에도 허용 필드만 새 이벤트로 복사합니다. 첨부 파일과 breadcrumbs도 전송하지 않습니다.
원래 API 응답과 서버 로그 정책은 유지합니다. Sentry에서 세부 원인을 확인할 때는 release와 코드 위치를 기준으로 추적합니다.

```bash
./gradlew test --tests '*ErrorMonitoringTest'
```

이 테스트는 빈 DSN, 잘못된 DSN, 4xx·취소 제외, HTTP 500·도메인 502의 단일 수집,
스케줄러 오류 수집과 직렬화된 이벤트의 민감정보 제외를 검증합니다.

참고: [Java SDK 설정](https://docs.sentry.io/platforms/java/configuration/options/),
[전송 데이터 필터링](https://docs.sentry.io/platforms/java/configuration/filtering/)
