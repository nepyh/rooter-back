# 마일스톤

루터 백엔드(`rooter-back`)의 **작업 진행 상태 단일 조망**이다.

- 작업 단위의 논의·리뷰는 **GitHub 이슈/PR** 에서 한다.
- 전체 진행과 **다음 작업 결정**은 이 문서에서 한다. 항목에는 이슈·PR 번호만 링크한다.
- 스키마 정본은 `nepyh/rooter-ddl` 이다. 스키마 관련 항목은 그쪽 이슈 번호를 함께 적는다.

> **기준 시점** — 아래 내용은 **2026-10-04** 기준으로 머지된 PR·브랜치·이슈와 운영(prod)·개발(dev) 서버 확인 결과를 근거로 채웠다.
> (첫 작성 2026-09-17, 09-17 이후 머지된 PR 52건과 운영 작업을 반영)
> 마일스톤 묶음과 진행 판정은 조정 대상이다. 상태를 바꿀 때는 아래 갱신 규칙을 따른다.

**작업 흐름** — 기능 PR 은 `develop` 으로 올린다 → 머지하면 dev 서버에 자동 배포 → 확인 후 `develop` → `main` 머지로 운영 배포.
DDL 이 바뀌면 dev DB(`rooter_dev`)에 먼저 ALTER 하고, 운영 DB(`postgres`)에는 **코드 머지 전에** ALTER 한다. (자세한 서버 정보는 README "서버 두 개 (prod / dev)")

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
| M2 | 컨벤션·구조 정비 | 5/6 | doing |
| M3 | AI 기능 포팅 (ai-poc) | 6/7 | doing |
| M4 | school·NICE 연동 | 6/8 | doing |
| M5 | 사용자 기능 | 7/9 | doing |
| M6 | 파일 저장·S3 | 4/5 | doing |
| M7 | 이슈 기반 기능 (#57~#59, 캘린더) | 3/4 | doing |
| M8 | AI 계획 생성·스케줄링 | 7/10 | doing |
| M9 | 퀴즈 고도화 | 7/11 | doing |
| M10 | 챗봇 재조정 | 5/5 | done |
| M11 | 교과서 카탈로그 | 4/5 | doing |
| M12 | API 응답·계약 정리 | 5/7 | doing |
| M13 | 인프라·배포 | 6/9 | doing |

## 블로커

- **RB-014 planboard 네이밍 통일** — `rooter-ddl` #10 open. DDL `schema.sql` 에 구 표기(`plan_boards`, `plan_board_id`)가 그대로 남아 있어 코드만 바꾸면 배포 시 터진다. **DDL 먼저.**
- **RB-043 소셜 로그인 실동작** — 구조만 머지된 상태. 운영 태스크 정의에 `GOOGLE_CLIENT_ID` / `APPLE_CLIENT_ID` 없음 (10-04 확인). 클라이언트 ID 미발급.
- **RB-060 Notification** — PR #83 닫힘. 코드는 `feature/notification-settings` 브랜치(4커밋)에만 있고 main 과 크게 벌어져 있다. main 위에 다시 얹을지 판단 필요. (프론트 요청 B-12)
- ~~RB-053 S3 모드 아바타 서빙 경로~~ — **해소** (아래 RB-053 참조)

## 열린 이슈 정합성

- #134 (시험 D-day 표시) — 닫힘 확인 (10-04).
- #58 (Quiz 서비스) — **마감 대상.** 퀴즈 기능은 M9 로 구현됨 (RB-061).
- #59 (잔디 streak) — **마감 대상.** main 에 머지됐고 dev 서버에서 동작 확인 (RB-062).
- #57 (Notification) — 열림 유지. RB-060 블로커 참조.
- #148 (MILESTONE.md) — 이 문서 PR(#149)로 마감.

## M1. 플랜보드 코어

목표: 플랜보드를 만들고, 고치고, 지우고, 태스크를 관리할 수 있다.
완료 기준: 생성→수정→태스크 완료→조회 플로우가 curl 로 끝까지 통과하고, 중복 생성이 1건으로 수렴한다.

- [x] done RB-001 플랜보드 CRUD 기본 — PR #64 (2026-08-27)
- [x] done RB-002 플랜보드·과목범위·태스크 수정/삭제 API (AI 없이 수동 관리) — PR #133 (09-17)
- [x] done RB-003 태스크 완료 처리/취소 API — PR #108 (09-16)
- [x] done RB-004 불가능 시간 삭제 API — PR #145 (09-17)
- [x] done RB-005 태스크 시간 검증 (`endTime <= startTime` 저장 방지) — PR #120 (09-16)
- [x] done RB-006 태스크 종료시각 자동 완료 확인 퀴즈 — PR #116 (09-16)
- [x] done RB-007 examDate 추가 + 조회 응답 D-day 계산 — PR #135 (09-17, developer) · main 반영 PR #182 (09-28) · 이슈 #134 닫힘
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
- [x] done RB-015 code 없이 나가던 에러 응답을 `ErrorResponse` 로 통일, 핸들러 try-catch 제거 — PR #172 (09-28)
- [x] done RB-016 테스트 격리 — `CatalogServiceTest` 가 `chapters` 를 직접 만들지 않아 새 DB 에서 실패하던 것 수정 — PR #236 (10-04, develop)

## M3. AI 기능 포팅 (ai-poc)

목표: 검증된 ai-poc 기능을 4단계로 `main` 에 옮긴다.
완료 기준: 4단계 전부 main 에 머지되고, 트랜잭션 스타일이 main 기준(blocking `transaction {}`)과 일치한다.

- [x] done RB-020 1단계 일일 퀴즈 — PR #100 (09-15)
- [x] done RB-021 2단계 일일 피드백 + AI 재조정(replan) — PR #102 (09-16)
- [x] done RB-022 3단계 실력 테스트(level test) — PR #104 (09-16)
- [x] done RB-023 4단계 AI 학습 계획 생성(plan-generation) — PR #106 (09-16)
- [x] done RB-024 LLM 프롬프트를 `resources/prompts/*.md` 로 분리 — PR #118 (09-16)
- [x] done RB-025 챗봇 기반 계획 재조정 — PR #132 (09-17)
- [ ] todo RB-026 `newSuspendedTransaction` → `suspendTransaction` 통일 — 미착수. 10-04 기준 **41곳 / 8파일**로 늘었다 (LLM 호출을 트랜잭션 밖으로 빼면서 사용처 증가)

## M4. school·NICE 연동

목표: 학교 정보(학사일정·시간표·교과서)를 플랜보드 입력으로 쓸 수 있다.
완료 기준: 회원가입 시 학교를 검색해 선택하고, 그 학교 기준 시험기간 후보가 조회된다.

- [x] done RB-030 NICE 래퍼 `module/school` (학교검색·시간표·학급·학사일정) — PR #46
- [x] done RB-031 회원가입용 학교 검색 자동완성 API — PR #124 (09-16)
- [x] done RB-032 학교-교과서 매핑 기반 추천 교과서 조회 — PR #122 (09-16)
- [x] done RB-033 NICE 학사일정 기반 시험기간 후보 조회 — PR #146 (09-17)
- [ ] todo RB-034 교시 시각 매핑 + 시험일정(`school_exam_periods`) — school 모듈 범위 밖, planboard 책임
- [ ] todo RB-035 교과서 데이터 수집 파이프라인 (`textbook`) — 설계만 완료 (Notion 정본). 운영 `school_textbook_adoptions` 는 비어 있음 (10-04, 추천 교과서가 안 나오는 원인)
- [x] done RB-036 학교 검색 결과가 없으면 502 가 나가던 문제 (NICE INFO-200 → 빈 목록) — PR #164 (09-28)
- [x] done RB-037 NICE 첫 페이지만 조회해 시험일정·하교시각이 틀리던 문제 (학년도 범위·페이지네이션) — PR #167 (09-28)

## M5. 사용자 기능

- [x] done RB-040 공부스타일 설문 — PR #110 (09-16)
- [x] done RB-041 할 일 탭 주간 과제 리스트 조회 API — PR #112 (09-16)
- [x] done RB-042 Google/Apple 소셜 로그인 (구조만) — PR #114 (09-16)
- [ ] blocked RB-043 소셜 로그인 실동작 — 클라이언트 ID/시크릿 미발급 (운영 태스크 정의에 없음, 10-04)
- [x] done RB-044 학생 프로필 등록 전 본인 정보 조회가 404 를 주던 문제 → 200 + 프로필 null — PR #158 (09-28)
- [x] done RB-045 비밀번호 변경 후에도 기존 토큰이 유효하던 문제 (tokenVersion, 새 토큰 발급) — PR #159 (09-28)
- [x] done RB-046 로그인 응답에 userId 추가 (토큰 디코드 의존 제거) — PR #180 (09-28)
- [x] done RB-047 아바타 다운로드 링크(avatarUrl) 응답 — PR #151 (09-26)
- [ ] todo RB-048 내 정보 응답에 학교 이름 (프론트 요청 B-17) — 지금은 `schoolId` 만 나감. NICE 조회로 DB 변경 없이 가능

## M6. 파일 저장·S3

목표: 아바타 등 파일 저장이 dev(local)와 prod(S3) 양쪽에서 동작한다.
완료 기준: prod 모드에서 업로드·조회가 되고, env 주입 체인 4단계가 문서와 일치한다.

- [x] done RB-050 `S3FileStorageImpl` (AWS SDK Kotlin + Roles Anywhere, dev-s3.conf + compose profile) — PR #129 (09-17)
- [x] done RB-051 아바타 업로드 시 0바이트로 저장되는 문제 — PR #131 (09-17)
- [x] done RB-052 아바타 업로드 용량/포맷 제한 — PR #147 (09-17)
- [x] done RB-053 S3 모드 아바타 서빙 경로 — `avatarUrl` 을 presigned URL 로 노출(PR #151) + 운영 `prod.conf`(S3) 전환 후 업로드 200 → presigned URL 다운로드 200·원본과 동일 확인 (10-01, RB-123)
- [ ] doing RB-054 배포 순서 체크포인트 정리 (DDL 제약 → 코드 머지 순서) — README 에 dev/prod 서버 표와 "DDL 은 dev 먼저" 안내 추가(PR #234). 10-01 `postponed_from_date` 미반영 배포 사고로 운영 할일 API 전체 500 (ALTER 후 복구) — 체크리스트화는 아직

## M7. 이슈 기반 기능 (#57~#59, 캘린더)

- [ ] blocked RB-060 Notification 서비스 (이슈 #57, 프론트 요청 B-12) — PR #83 닫힘, 코드는 `feature/notification-settings` 4커밋(notification module + Expo push)에만 있음. main 위에 재정렬 필요
- [x] done RB-061 Quiz 서비스 (이슈 #58) — ai-poc 포팅(RB-020) 이후 M9 로 고도화 완료 · 이슈 #58 마감 대상
- [x] done RB-062 프로필 잔디 심기(streak) (이슈 #59) — `64a58b0` + `15a3e89`(리네임 후 컴파일 수정) · dev 서버 `GET /users/{id}/streak?start&end` 200 확인 (10-04) · 이슈 #59 마감 대상
- [x] done RB-063 캘린더 (월별 계획량·날짜별 조회·개인 일정 CRUD) — `531c176` + 일정 수정 API PR #181 (09-28) · dev 서버 `GET /calendar?start&end` 200 확인 (10-04)

## M8. AI 계획 생성·스케줄링

목표: AI 가 만든 계획이 학생의 실제 하루(수면·학교·학원·다른 계획)와 겹치지 않고, 기간 전체에 빠짐없이 잡힌다.
완료 기준: 등교일·공휴일·기존 할일을 피해 배치되고, 60일 이상 기간도 하루도 빠지지 않는다.

- [x] done RB-070 불가능 시간 등록 시 AI 계획 태스크가 00:00 부터 배치되던 문제 — PR #154 (09-26)
- [x] done RB-071 평일에는 하교 후에만 계획 배치 (등교 전 아침 배치 금지) — PR #202 (09-29)
- [x] done RB-072 공휴일·방학·재량휴업일(NICE 학사일정)은 평일이어도 아침부터 배치 — PR #206 (09-29)
- [x] done RB-073 긴 기간 계획이 8~10일치만 생성되던 문제 (14일 단위 분할 생성 + 일수 검증·재시도) — PR #210 (09-30) · 실제 LLM 60일 생성 확인
- [x] done RB-074 새 계획이 다른 플랜보드 할일과 겹치지 않게 (앞뒤 10분) (프론트 요청 B-05) — PR #220 (10-01)
- [x] done RB-075 날짜별 바쁜 시간·빈 시간 조회 API `GET /busy-times` (B-04) — PR #222 (10-01) · 운영 200 확인
- [x] done RB-076 서버 "오늘"을 한국 시간 기준으로 (UTC 00~09시 어제 처리 문제) (B-03) — PR #194 (09-29)
- [ ] todo RB-077 자정을 넘기는 할일 (B-09) — 지금은 `INVALID_TIME_RANGE` 로 거부. 정책 결정 필요
- [ ] doing RB-078 같은 날 dailyPlanId 가 여러 개(보드 여러 개)일 때 챗봇·퀴즈 기준 (B-07) — 일일 퀴즈 생성에 `planBoardId` 추가, 생략 시 학습 범위 있는 보드 우선 (이슈 #241, PR #246 open, 테스트 199건 통과). 챗봇은 경로의 dailyPlanId 로 이미 보드가 정해짐. 프론트가 `planBoardId` 를 보내야 완결
- [ ] todo RB-079 일일 퀴즈 복습 할일·챗봇 재조정·퀴즈 실패 밀기도 다른 보드 할일을 피하도록 — 지금은 같은 보드의 그날 할일만 봄

## M9. 퀴즈 고도화

목표: 태스크 완료 확인 퀴즈와 일일 퀴즈가 정답을 미리 보거나 우회할 수 없고, 틀린 이유와 풀이를 보여준다.
완료 기준: 완료 버튼 → 퀴즈 → 통과 시 완료가 종료 시각과 무관하게 동작하고, 문제마다 즉시 채점·풀이가 나온다.

- [x] done RB-080 일일 퀴즈 중복 생성·제출 실패 (LLM 호출을 트랜잭션 밖으로, 행 잠금) — PR #186 (09-28)
- [x] done RB-081 퀴즈 제출 후 복습 태스크를 스케줄러로 배치, AI 약점 분석 실패해도 제출 성공 — PR #188 (09-29)
- [x] done RB-082 태스크 퀴즈가 학생·과제 자체를 묻던 문제 (학년·학습 범위 전달) — PR #196 (09-29)
- [x] done RB-083 일일 퀴즈 제출 후 틀린 문제 풀이(explanation) — PR #204 (09-29) · `rooter-ddl` #19
- [x] done RB-084 태스크 퀴즈 불합격 시 오늘 남은 계획 15분 뒤로 밀기 — PR #208 (09-29)
- [x] done RB-085 태스크 퀴즈 문제마다 답 저장·즉시 채점·재답변 차단, 보기별 짧은 이유·자세한 풀이 (B-16 포함) — PR #214 (09-30) · `rooter-ddl` #23 · PR #224 (10-01)
- [x] done RB-086 퀴즈 응답에 과목 정보 `subjects` / `subject` (B-15) — PR #228 (10-01)
- [ ] doing RB-087 완료 버튼으로 퀴즈를 열면 종료 시각 전에도 바로 생성 (`GET .../quiz` 에서 생성) — PR #236 develop 머지 (10-04) · dev 서버에서 종료 시각 전 열기 200·6초·5문항 확인. **main(운영) 반영 대기**
- [ ] todo RB-088 학습 범위 없는 날 퀴즈가 엉뚱해지는 문제 (B-08)
- [ ] todo RB-089 보기 순서 섞기 (AI 가 정한 순서 그대로 나가 정답 위치가 쏠릴 수 있음) — DB 변경 없음
- [ ] doing RB-129 퀴즈 문항 수 가변 (#243) — 공부 시간 기준 30분 이하 4·1시간 이하 5·초과 7문항 (태스크 퀴즈는 할일 estimatedMinutes, 일일 퀴즈는 완료 할일 합), 태스크 퀴즈 통과 70점 이상 (`passCount` 응답 추가). 문항이 덜 저장되면 통과 못 하던 문제도 수정 — PR #247 open (`3a551e4`·`e4ac66c`·`0618633`) · `./gradlew test` 전체 통과 · 프론트(`rooter-front`)는 `questions.length`·서버 `passed` 기준이라 영향 없음 확인. DB 변경 없음

## M10. 챗봇 재조정

목표: 챗봇에 사정을 말하면 그날 계획이 현실적으로 바뀌고, 답변이 실제 변경과 일치한다.

- [x] done RB-090 채팅 재조정 시 완료한 태스크가 초기화되던 문제 — PR #155 (09-26)
- [x] done RB-091 시험 날짜를 물으면 오늘 날짜로 답하던 문제 (플랜보드 시험일·D-day 전달) — PR #190 (09-29)
- [x] done RB-092 계획을 안 바꾸고도 "조정해 드릴게요" 라고 답하던 문제 (서버가 겹침 판단·답변 보정) — PR #198 (09-29)
- [x] done RB-093 재조정이 안 겹치는 태스크까지 이른 시간으로 옮기던 문제 — PR #200 (09-29)
- [x] done RB-094 "내일로 미뤄줘" — 다른 날로 미루기, 이틀 연속 미루기 금지 — PR #230 (10-01) · `rooter-ddl` #25 · 실제 LLM 로 미루기·날짜 계산 확인

## M11. 교과서 카탈로그

- [x] done RB-100 과목 범위에 다른 교과서의 단원을 넣어도 저장되던 문제 — PR #174 (09-28)
- [x] done RB-101 과목 범위를 단원 계층(대단원→소단원) 순서로 판정·선택 — PR #184 (09-28)
- [x] done RB-102 교과서 표지 `coverImageUrl` — PR #213 (09-30) · `rooter-ddl` #21 · 표지 50권 S3 업로드 + `cover_image_key` 입력, 운영 50/50 확인 (10-01)
- [x] done RB-103 교과서 출판사 이름 `publisherName` (B-10) — PR #226 (10-01) · 운영 50/50 확인
- [ ] todo RB-104 3학년 교과서 일부 표지가 2022판 디자인 (DB 내용은 2015판) — id 7·28·39 교체 검토

## M12. API 응답·계약 정리

- [x] done RB-110 할일 응답에 `dailyPlanId` 추가 — PR #166 (09-28)
- [x] done RB-111 플랜보드·피드백 `createdAt` 에 시간대 오프셋 (`timestamptz`) — PR #176 (09-28)
- [x] done RB-112 Swagger servers 에 AWS 배포 서버 주소 — PR #169 (09-28)
- [x] done RB-113 FeedbackApi Swagger 에 difficulty 허용값 명시 — PR #144 (09-17)
- [x] done RB-114 플랜보드 생성·수정에 examDate, 응답 노출 (main) — PR #182 (09-28)
- [ ] todo RB-115 태스크 응답에 생성 출처 `source` (AI 생성 / 사용자 추가 / 복습) (B-13) — DDL 필요
- [ ] todo RB-116 JWT 만료(14일) 후 처리 (B-06) — 리프레시 토큰 여부 정책 결정 필요

## M13. 인프라·배포

목표: 운영(prod)과 개발(dev) 서버가 분리되어 있고, 운영에 나가기 전에 dev 에서 확인할 수 있다.

- [x] done RB-120 배포에 쓰이지 않는 ECS task definition 스냅샷 제거 — PR #163 (09-27)
- [x] done RB-121 컨테이너 기동에 필요한 LLM·oauth 환경변수 배선 누락 — PR #216 (09-30)
- [x] done RB-122 `prod.conf` development 모드 해제 + `logback.xml` 기본 INFO (SQL·개인정보 로그 제거) — PR #218 (10-01)
- [x] done RB-123 운영을 `prod.conf` 로 전환 (Swagger 비공개, S3 저장) — ECS 태스크 정의 rev 129 (10-01) · health 200 / swagger 404 / 회원가입·로그인 / S3 아바타 / 계획 생성 확인, 되돌리기 = rev 128
- [x] done RB-124 dev 서버 — ECS `rooter-dev` + 같은 RDS 의 `rooter_dev` DB(마스터 데이터만 복사) + `develop` 자동 배포 — PR #234 (10-04) · 첫 배포 성공, health·swagger 200
- [x] done RB-125 dev 전용 DB 계정 `rooter_dev_app` — dev DB 소유, 운영 테이블 읽기 permission denied 확인 (10-04)
- [ ] todo RB-126 고정 도메인 연결 (B-02) — 지금은 ECS 자동 주소. 도메인 결정 필요
- [ ] todo RB-127 서버 DB 초기화 여부 (B-01) + 운영에 남은 테스트 계정 정리 — 결정 필요
- [ ] todo RB-128 운영 `textbooks.file_url` 잔여 컬럼 정리 — DDL main 은 삭제했는데 운영 DB 에는 남아 있음 (10-04 확인)

## 다음 후보 (우선순위)

| 우선 | 항목 | 규모 | 의존 | 비고 |
|---|---|---|---|---|
| 1 | RB-087 완료 버튼 퀴즈 운영 반영 | S | 없음 | dev 확인 끝. `develop` → `main` 머지만 하면 됨 |
| 2 | RB-089 보기 섞기 · RB-079 다른 보드 피하기 확장 | S | 없음 | DB 변경 없음 |
| 3 | RB-048 내 정보에 학교 이름 (B-17) | S | 없음 | NICE 조회, DB 변경 없음 |
| 4 | RB-026 트랜잭션 스타일 통일 | S | 없음 | 41곳 / 8파일, 기계적 치환 |
| 5 | RB-088 학습 범위 없는 날 퀴즈 (B-08) | M | 디자인 | 범위 없을 때 동작 정의 필요 |
| 6 | RB-115 태스크 source 필드 (B-13) | M | rooter-ddl | DDL 선행, dev DB 먼저 |
| 7 | RB-060 Notification (B-12) | L | 브랜치 재정렬 | `feature/notification-settings` 를 main 위에 다시 얹기 |
| 8 | RB-014 planboard 네이밍 | M | rooter-ddl #10 | DDL 선행 필요 (블로커) |
| - | 정책 결정 대기 | - | 팀 결정 | RB-077 자정 넘김 · RB-116 JWT 만료 · RB-126 도메인 · RB-127 DB 초기화 |

## 폐기/보류

- (없음) 폐기 항목은 이유와 함께 `dropped` 로 남긴다. 예: `- [ ] dropped RB-0NN <항목> — <이유>`
