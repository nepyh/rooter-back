# 마일스톤

루터 백엔드(`rooter-back`)의 **작업 진행 상태 단일 조망**이다.

- 작업 단위의 논의·리뷰는 **GitHub 이슈/PR** 에서 한다.
- 전체 진행과 **다음 작업 결정**은 이 문서에서 한다. 항목에는 이슈·PR 번호만 링크한다.
- 스키마 정본은 `nepyh/rooter-ddl` 이다. 스키마 관련 항목은 그쪽 이슈 번호를 함께 적는다.

> **기준 시점** — 아래 내용은 2026-09-17 기준으로 머지된 PR·브랜치·이슈를 근거로 채웠다.
> 마일스톤 묶음과 진행 판정은 조정 대상이다. 상태를 바꿀 때는 위 갱신 규칙을 따른다.

## 상태 어휘

| 상태 | 의미 | 표기 |
|---|---|---|
| `done` | 완료. **근거가 있어야 한다** | `- [x] done` |
| `doing` | 착수했고 진행 중 | `- [ ] doing` |
| `todo` | 예정. 아직 착수 안 함 | `- [ ] todo` |
| `blocked` | 착수 불가. 차단 원인을 적는다 | `- [ ] blocked` |
| `dropped` | 하지 않기로 결정. 이유를 적는다 | `- [ ] dropped` |

체크박스는 `done` 의 시각화 전용이다. **`[x]` 는 반드시 `done` 과 짝을 이룬다** (5개 상태를 체크박스로 표현하지 않는다).

## 에이전트 갱신 규칙

1. 작업 시작 → 해당 항목을 `doing` 으로 바꾸고 한 줄 추가한다. 없으면 추가하고 **ID = 마지막 번호 + 1**.
2. 커밋/PR 직후 → 근거를 채운다 (짧은 SHA · PR 번호 · 검증 결과). PR 이 open 이면 `doing`, **머지된 뒤에 `done`**.
3. `done` 은 **검증 증거가 있을 때만**. 확인하지 않은 완료 표기를 금지한다. 증거가 없으면 `doing` 으로 둔다.
4. 요약 표 · 블로커 · 다음 후보를 **같은 커밋에서 함께** 갱신한다. 문서가 코드보다 뒤처지면 신뢰를 잃는다.
5. ID 재번호 금지 (커밋·이슈가 참조한다).
6. 이 문서만 고치는 커밋은 `edit:`.

## 요약

| # | 마일스톤 | 진행 | 상태 |
|---|---|---|---|
| M1 | 플랜보드 코어 | 9/10 | doing |
| M2 | 컨벤션·구조 정비 | 3/4 | doing |
| M3 | AI 기능 포팅 (ai-poc) | 6/7 | doing |
| M4 | school·NICE 연동 | 4/6 | doing |
| M5 | 사용자 기능 | 3/4 | doing |
| M6 | 파일 저장·S3 | 3/5 | doing |
| M7 | 미착수 (이슈만 존재) | 0/3 | todo |

## 블로커

- **RB-014 planboard 네이밍 통일** — `rooter-ddl` #10 open. DDL `schema.sql` 에 구 표기(`plan_boards`, `plan_board_id`)가 그대로 남아 있어 코드만 바꾸면 배포 시 터진다. **DDL 먼저.**
- **RB-053 S3 모드 아바타 서빙 경로** — `storage.type = s3` 이면 `/api/files` 정적 라우트가 등록되지 않아 아바타 서빙 경로가 없다. presigned URL 을 노출하는 API 가 아직 미정의.
- **RB-043 소셜 로그인 실동작** — 구조만 머지된 상태. 클라이언트 ID/시크릿 미발급.

## 열린 이슈 정합성

- #134 (시험 D-day 표시) — **구현이 이미 머지됨**(PR #135). 이슈 마감 대상.
- #57 / #58 / #59 — 아래 M7 참조.

## M1. 플랜보드 코어

목표: 플랜보드를 만들고, 고치고, 지우고, 태스크를 관리할 수 있다.
완료 기준: 생성→수정→태스크 완료→조회 플로우가 curl 로 끝까지 통과하고, 중복 생성이 1건으로 수렴한다.

- [x] done RB-001 플랜보드 CRUD 기본 — PR #64 (2026-08-27)
- [x] done RB-002 플랜보드·과목범위·태스크 수정/삭제 API (AI 없이 수동 관리) — PR #133 (09-17)
- [x] done RB-003 태스크 완료 처리/취소 API — PR #108 (09-16)
- [x] done RB-004 불가능 시간 삭제 API — PR #145 (09-17)
- [x] done RB-005 태스크 시간 검증 (`endTime <= startTime` 저장 방지) — PR #120 (09-16)
- [x] done RB-006 태스크 종료시각 자동 완료 확인 퀴즈 — PR #116 (09-16)
- [x] done RB-007 examDate 추가 + 조회 응답 D-day 계산 — PR #135 (09-17) · 이슈 #134 마감 필요
- [x] done RB-008 daily_plans 중복 생성 레이스 방지 — insertIgnore `c18cf9d` + `rooter-ddl` #11 closed(유니크 제약)
- [x] done RB-009 DB 통합 테스트 (`PlanBoardServiceTest`, 18케이스) — `a5680c7`
- [ ] todo RB-010 scheduler DAO 전환 — 미지시 항목

## M2. 컨벤션·구조 정비

목표: 새 module 을 추가할 때 따라야 할 패턴이 문서와 코드 양쪽에 고정되어 있다.
완료 기준: `AGENTS.md` 의 패턴 설명과 `main` 코드가 일치하고, 단일 테이블 DSL 직접 호출이 남아 있지 않다.

- [x] done RB-011 예외 응답 중앙화 (StatusPages + `ErrorResponse(code, message)`, 라우트 try/catch 금지) — `065a39a`
- [x] done RB-012 ORM `~Table` + `~Row` 쌍 패턴 통일 (26 테이블 / 42 파일) — PR #63 (09-17) · 이슈 #62 closed
- [x] done RB-013 `AGENTS.md` 작성 (구조·module 추가 절차·컨벤션) — PR #65 (08-28)
- [ ] blocked RB-014 planboard 네이밍 통일 (`plan_boards` → `planboards`) — `rooter-ddl` #10 open

## M3. AI 기능 포팅 (ai-poc)

목표: 검증된 ai-poc 기능을 4단계로 `main` 에 옮긴다.
완료 기준: 4단계 전부 main 에 머지되고, 트랜잭션 스타일이 main 기준(blocking `transaction {}`)과 일치한다.

- [x] done RB-020 1단계 일일 퀴즈 — PR #100 (09-15)
- [x] done RB-021 2단계 일일 피드백 + AI 재조정(replan) — PR #102 (09-16)
- [x] done RB-022 3단계 실력 테스트(level test) — PR #104 (09-16)
- [x] done RB-023 4단계 AI 학습 계획 생성(plan-generation) — PR #106 (09-16)
- [x] done RB-024 LLM 프롬프트를 `resources/prompts/*.md` 로 분리 — PR #118 (09-16)
- [x] done RB-025 챗봇 기반 계획 재조정 — PR #132 (09-17)
- [ ] todo RB-026 `newSuspendedTransaction` 19곳 / 7파일 → `suspendTransaction` 통일 — 미착수

## M4. school·NICE 연동

목표: 학교 정보(학사일정·시간표·교과서)를 플랜보드 입력으로 쓸 수 있다.
완료 기준: 회원가입 시 학교를 검색해 선택하고, 그 학교 기준 시험기간 후보가 조회된다.

- [x] done RB-030 NICE 래퍼 `module/school` (학교검색·시간표·학급·학사일정) — PR #46
- [x] done RB-031 회원가입용 학교 검색 자동완성 API — PR #124 (09-16)
- [x] done RB-032 학교-교과서 매핑 기반 추천 교과서 조회 — PR #122 (09-16)
- [x] done RB-033 NICE 학사일정 기반 시험기간 후보 조회 — PR #146 (09-17)
- [ ] todo RB-034 교시 시각 매핑 + 시험일정(`school_exam_periods`) — school 모듈 범위 밖, planboard 책임
- [ ] todo RB-035 교과서 데이터 수집 파이프라인 (`textbook`) — 설계만 완료 (Notion 정본)

## M5. 사용자 기능

- [x] done RB-040 공부스타일 설문 — PR #110 (09-16)
- [x] done RB-041 할 일 탭 주간 과제 리스트 조회 API — PR #112 (09-16)
- [x] done RB-042 Google/Apple 소셜 로그인 (구조만) — PR #114 (09-16)
- [ ] blocked RB-043 소셜 로그인 실동작 — 클라이언트 ID/시크릿 미발급

## M6. 파일 저장·S3

목표: 아바타 등 파일 저장이 dev(local)와 prod(S3) 양쪽에서 동작한다.
완료 기준: prod 모드에서 업로드·조회가 되고, env 주입 체인 4단계가 문서와 일치한다.

- [x] done RB-050 `S3FileStorageImpl` (AWS SDK Kotlin + Roles Anywhere, dev-s3.conf + compose profile) — PR #129 (09-17)
- [x] done RB-051 아바타 업로드 시 0바이트로 저장되는 문제 — PR #131 (09-17)
- [x] done RB-052 아바타 업로드 용량/포맷 제한 — PR #147 (09-17)
- [ ] blocked RB-053 S3 모드 아바타 서빙 경로 — presigned URL 노출 API 미정의
- [ ] todo RB-054 배포 순서 체크포인트 정리 (DDL 제약 → 코드 머지 순서)

## M7. 미착수 (이슈만 존재)

- [ ] doing RB-060 Notification 서비스 (이슈 #57) — 업스트림 브랜치 `feature/notification-settings` 에 4커밋(notification module + Expo push), **PR 없음**, main 기준 101 뒤처짐
- [ ] todo RB-061 Quiz 서비스 (이슈 #58) — ai-poc 포팅으로 상당 부분 선행됨, 이슈 재정의 필요
- [ ] todo RB-062 프로필 잔디 심기(streak) (이슈 #59)

## 다음 후보 (우선순위)

| 우선 | 항목 | 규모 | 의존 | 비고 |
|---|---|---|---|---|
| 1 | RB-026 트랜잭션 스타일 통일 | S | 없음 | 기계적 치환, 컴파일만 검증하면 됨 |
| 2 | RB-014 planboard 네이밍 | M | rooter-ddl #10 | DDL 선행 필요 (블로커) |
| 3 | RB-053 S3 아바타 서빙 경로 | M | 스토리지 API 결정 | prod 배포 전 필요 |
| 4 | RB-060 Notification | L | 브랜치 재정렬 | behind 101 — main 위에 다시 얹는 판단 필요 |
| 5 | RB-034 교시 시각 매핑 | M | RB-033 | 시험기간 → 플랜 생성 연결 |
| 6 | RB-061 / RB-062 | M | 없음 | 이슈 재정의 선행 |

## 폐기/보류

- (없음) 폐기 항목은 이유와 함께 `dropped` 로 남긴다. 예: `- [ ] dropped RB-0NN <항목> — <이유>`
