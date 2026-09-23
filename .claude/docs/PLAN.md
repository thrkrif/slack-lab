# 1단계(P0) 작업 계획 — slack-lab

**상태: 승인됨 · 구현 진행 중** (Consensus 1회전 완료: Architect·Critic 모두 APPROVE-WITH-IMPROVEMENTS, 개선안 병합됨. 마일스톤별 진행은 아래 `진행 상태`)
정본: 이 파일 (`.omc/plans/stage1-p0-plan.md`는 consensus 산출물 사본)
근거: [`PRD.md`](PRD.md) §4 P0·§5, [`ARCHITECTURE.md`](ARCHITECTURE.md) §2–§4·§8·§10–§11, [`AGENTS.md`](../../AGENTS.md)
범위: **P0-1 ~ P0-8만.** 큐·Redis·RAG·LangGraph·스레드 문맥·"확인 중" 표시는 넣지 않는다 (AGENTS.md 규칙 1).

## 진행 상태

세션을 작업 단위로 교체하며 진행한다. 새 세션은 이 절과 `docs/EXPERIMENT-LOG.md`를 먼저 읽는다. 마일스톤을 끝낼 때마다 갱신한다.

- 현재 브랜치 기준: `develop` (기본 브랜치). 기능은 `feature/mN-…`에서 작업한다.
- [x] **M0 사전 준비** — 완료
  - [x] Ollama 0.34.0 설치, `qwen2.5:7b` 실응답·모델 정보 기록 (`docs/EXPERIMENT-LOG.md` §1·§2.1)
  - [x] ngrok 3.39.11 설치
  - [x] Slack 앱 생성·스코프 설치, 토큰·Signing Secret을 `.env`에 저장 (사용자)
  - [x] ngrok authtoken 등록 (사용자), 60초 지연 실측 통과 — 예산 조정 없음 (`docs/EXPERIMENT-LOG.md` §2.3)
  - [x] `.env.example` 작성 (키 이름만)
- [x] **M1 프로젝트 골격** — 완료 (병합됨, #4)
  - [x] Gradle Wrapper 8.14.3·Java 21·Boot 3.4.1, 패키지 `com.slack.lab`, 설정 record 4종(키는 M1 목록 그대로)
  - [x] `.env` 로드 후 `bootRun` → `/health` 200 확인
  - [x] `SLACK_SIGNING_SECRET` 누락 시 기동 실패, 로그에 `slack.signingSecret` 원인 출력 (A3)
  - [x] `./gradlew build` 통과 (설정 바인딩·`/health` 테스트 4건)
  - [x] 도구 결정: Spotless·gitleaks·CI 미도입, 의존성 없는 `.githooks/pre-commit` 도입 (`AGENTS.md` Git 규칙)
- [x] **M1.5 기한 강제 스파이크** — 완료 (PR 병합 대기)
  - [x] 스텁 3종 × 방식 3종 측정, 서버 쪽 소켓 종료 감지로 판정 (`docs/EXPERIMENT-LOG.md` §3, 재현: `docs/spikes/DeadlineSpike.java`)
  - [x] 채택: A2 (`sendAsync` + 호출별 `cancel(true)`), `request.timeout`은 헤더까지만 덮음
- [x] **M2 서명 검증 + 수신 컨트롤러** — 완료 (병합됨, #6)
  - [x] `SlackSignatureVerifier`·`SlackEventController`, curl 검증 (`docs/EXPERIMENT-LOG.md` §2.5)
  - [x] ngrok URL을 Slack Request URL에 등록해 **Verified** 확인 (사용자, 2026-09-22)
- [x] **M3 값 객체 + 중복 억제** — 완료 (병합됨, #7)
  - [x] `SlackMessageEvent`(shouldIgnore·replyThreadTs·promptText), `EventDeduplicator`·`AttemptHandle`·`ClaimResult`·`ProcessingState`
  - [x] 단위 테스트 20건: 동시 64회 선점 1승(A9), 소유자 아닌 전이 거절, TTL 경계, 청소가 실행 중 엔트리를 안 지움, FAILED 재선점, dedup 스위치. 선점을 비원자적(get 후 판단)으로 바꾸면 동시성 테스트가 실패함을 확인
  - [x] A13: `grep -rE "jakarta\.servlet|org\.springframework\.http" src/main/java/com/slack/lab/event/` 0건
- [x] **M4 LLM 클라이언트** — 완료 (PR 병합 대기)
  - [x] `LlmClient`/`OpenAiCompatibleLlmClient`(M1.5 A2 전송)/`EchoLlmClient`/`LlmConfig`, 기동 시 fail-fast 모델 확인+웜업
  - [x] 실제 Ollama 호출 성공, 잘못된 모델 기동 실패, 우회 플래그 확인 (`docs/EXPERIMENT-LOG.md` §2.6)
  - [x] codex critic 2회전(REQUEST CHANGES → 4건 수정 → 승인, `docs/EXPERIMENT-LOG.md` §2.8)
- [x] **M5 Slack 발신 클라이언트** — 완료 (병합됨, #11)
  - [x] `SlackClient`(3분류 `SlackSendResult`: Success/Failed/Unknown), M1.5 A2 전송, 예산 소진 시 미발신(A15 대비)
  - [x] 실제 스레드 답글 성공, `ok:false`(스코프 부족·미초대·잘못된 채널) 각각 분류 확인 (`docs/EXPERIMENT-LOG.md` §2.7)
  - [x] 사람 조작: Slack 앱에 `chat:write` 스코프 추가 후 재설치, 테스트 채널에 봇 초대
  - [x] codex critic 2회전 모두 승인: 1차(§2.8, interrupt 미취소 결함 발견·수정) → 2차(ok 필드·5xx·부분 처리 오류코드를 결과 불명으로 재분류) → OKAY, WATCH 등급의 비차단 보완 의견 2건은 §2.9에 후속 과제로 기록
- [x] **M6 핸들러 + 흐름 조립** — 완료 (병합됨, #12)
  - [x] `HandlingResult`·`SlackEventHandler`(LLM→답변/실패안내, markSending 게이트, sealed switch)·컨트롤러 연결(dedup+handler), `AckLoggingFilter`
  - [x] 단위 테스트 27건(handler 11 + controller 11 + AckLoggingFilter 5 신규 포함), A13 재확인
  - [x] curl 유도: A5(bot_id 무시), 실제 LLM+Slack 왕복 성공(답변 경로), A6(b) 실패 안내 경로, A8(ok:false invalid_thread_ts로 실증)
  - [x] A1 실제 멘션 왕복 성공 — 막힘 원인은 Slack 앱의 Socket Mode 활성화였음(§2.9)
  - [x] code-reviewer(대체) 1차 REQUEST CHANGES 6건 중 코드 5건 반영(§2.10), 6번째(브랜치 분리)는 별도 완료(M5 PR #11 분리 병합)
  - [x] codex critic 재검토는 usage limit으로 실패(§2.11) → code-reviewer(대체) 2차 REQUEST CHANGES 1건(예외 가드 회귀 테스트 0건) + MEDIUM 2건 모두 반영: 예외 주입 테스트 2건, `llmBudgetMs()` 헬퍼로 slow-mode에도 clamp 적용, `AckLoggingFilter` positive guard 전환. `ARCHITECTURE.md` §2 갱신(규칙 9)
  - [x] PR #12 병합 완료(2026-09-23). 반영 안 한 LOW 5건·Open Question 1건은 P1 후속 과제로만 남김(§2.11)
- [x] **M7 계측** — 완료. 새 코드 없이 로그 grep 집계 방법을 문서화(`docs/EXPERIMENT-LOG.md` §4): 재전송 횟수·중복 억제 수·중복 답글 수(dedup 끈 배치 전용)·LLM 소요 시간(답변/실패 안내 분리)·`ack_delivered`. **아직 실제 로그로 실행해보지 않았다 — M8-1에서 처음 돌리며 검증**
- [ ] M8 — 미착수
### 다음 세션 핸드오프

단위를 끝낼 때마다 이 소절을 덮어쓴다. 재현 가능한 사실만 적고, 진행 체크는 위 목록이 정본이다.

- **다음 작업(우선순위 순)**:
  1. M8(실험): PLAN §3 M8 절차대로 slow-mode 경계 실험(echo 고정, 2.5/3.0/5/30s × 5회) → 실제 LLM 실험 → dedup on/off 대비 → 오류 유도 매트릭스(A2·A5·A6·A7·A8·A10·A15·A16). `docs/EXPERIMENT-LOG.md` §4의 grep 명령을 여기서 처음 실행해보고, 필드명 오타 등이 있으면 그 자리에서 고친다.
  2. M8 완료 후 `docs/EXPERIMENT-LOG.md`·`AGENTS.md` 현재 단계 줄 갱신, PRD §6 1단계 체크박스 3개를 실측 데이터로 채운다(§3 M8-8). **1단계 완료(`develop`→`main` 병합, 태그 `v0.1.0`)는 사용자 승인 필요 — 자동 진행하지 않는다.**
  3. P1 후속 과제로만 남기는 것들(병합 차단 아님, M6 리뷰에서 발견): codex WATCH 2건(제출-후-예약 실패 시 Unknown 미분류, 요청준비시간 미차감), code-reviewer 2차 LOW 5건(§2.11 — `SlackClient` 워치독 +500ms, `EventDeduplicator.markFailed`의 SENDING 경로 오탐성 WARN 등), 설정값 상호 불변식(`llm.deadline-ms+slack.send-deadline-ms<=processing.total-deadline-ms`) 기동 시 미검증.
- **LLM 품질 튜닝(2026-09-23)**: qwen2.5:7b가 느슨한 지시에서 중국어·영어를 섞어 답한 사례 실측. `OpenAiCompatibleLlmClient`의 시스템 프롬프트를 강화하고 `temperature=0.3` 추가로 해결 확인(실제 멘션 재검증 완료).
---

## 1. 요구사항 요약

Slack `app_mention` → 서명 검증 → 중복 억제 → Ollama 답변 → 원 메시지 스레드에 답글. 전부 **동기**로 처리하고,
그 결과 생기는 **3초 초과·재전송**을 로그 숫자로 관찰해 `docs/EXPERIMENT-LOG.md`에 남긴다 (P0-7이 이 단계의 진짜 산출물).

## 2. 수락 기준 (테스트 가능 형태)

| # | 기준 | 판정 방법 |
|---|---|---|
| A1 | 실제 Slack 멘션 1건이 원 메시지 스레드에 답글로 돌아온다 (`thread_ts` = 원 `ts`, 스레드 안 멘션이면 원 `thread_ts`) | 실제 워크스페이스 왕복. 스크린샷 + 로그 |
| A2 | 서명 불일치·5분 초과 요청은 401, LLM·발신 호출 0회 | 잘못된 서명 curl + 단위 테스트 |
| A3 | Signing Secret 비어 있으면 **기동 실패** | 환경변수 없이 `bootRun` |
| A4 | 서명 유효 + 본문 깨짐 → 400, `url_verification` → 200 + challenge | curl + Slack Request URL 등록 성공 |
| A5 | `bot_id`/`subtype` 이벤트는 200으로 무시, LLM·발신 호출 0회 | **유효 서명 + `bot_id`(또는 `subtype`) 포함 본문 curl** → 200, 로그에 무시 사유, 호출 0회. 실제 재유입 루프 관측은 선택 절차(답변에 `<@봇ID>`를 임시 삽입)로만 수행 — `app_mention`만 구독하면 봇 답글은 자연히 재유입되지 않는다 |
| A6 | LLM 실패 시 실패 안내 1회 전송, 안내 실패 시 event_id·단계가 로그에 남음 | 유도 (a) Ollama 프로세스 중단, (b) `llm.verify-model-on-startup=false`로 잘못된 모델 ID를 런타임 호출. 스레드·로그 확인 |
| A7 | LLM 50초 기한 초과 시 호출 취소, 늦은 결과는 답글로 나가지 않음 | 스텁 3종(헤더만 보내고 정지 / 본문 중간 절단 후 정지 / accept 후 무응답)을 `llm.base-url`로 겨냥. 취소 후 **소켓이 실제로 닫히는지** 확인 |
| A8 | `chat.postMessage`가 HTTP 200 + `ok:false`여도 실패로 분류, 컨트롤러 밖으로 예외 누출 없음 | 잘못된 토큰/채널로 유도. 컨트롤러가 200을 결정하며, 응답이 Slack에 전달됐는지는 `ack_delivered`로 별도 관측 |
| A9 | 동일 event_id 동시 N회 전달 시 핸들러 실행 1회 | 동시성 단위 테스트(스레드 N개 + 래치) |
| A10 | 전송 결과 불명 → `UNKNOWN`, 10분간 자동 재발신 없음 | `slack.base-url`을 끊김 스텁으로 향하게 해 발신 중 연결 끊김 유도 + 재전송 |
| A11 | 재전송 횟수·중복 억제 수·중복 답글 수·LLM 소요 시간이 event_id 단위 로그로 집계된다. **정상 답변과 실패 안내를 분리 집계**하고 `ack_delivered`를 포함 | 실험 후 로그 집계 스크립트/수동 표 |
| A12 | `EXPERIMENT-LOG.md`에 장비·Ollama 버전·모델 ID·digest·양자화·토큰 상한이 기록됨 | 문서 확인 (PRD §5 P1 환경 고정 전제). digest·양자화는 M0에서 미리 확보 |
| A13 | `SlackEventHandler` 계열에 HTTP 타입 import 없음 | `grep -rE "jakarta\.servlet|org\.springframework\.http" src/main/java/com/slack/lab/event/` 결과 0건 |
| A14 | `./gradlew build` 통과 — **단, 완료 판정은 A1~A12·A15·A16** | AGENTS.md 규칙 5 |
| A15 | LLM이 기한을 소진해 발신 예산이 남지 않으면 **발신 0회**, 상태 `FAILED`, 로그에 실패 단계 명시 | `llm.deadline-ms`를 총 예산에 근접하게 낮춘 스텁 실험 |
| A16 | 처리 시작(t0)부터 컨트롤러의 응답 결정까지 총 소요가 **60초 이내** | 단조 시계 로그. 초과하면 결함으로 기록하고 초과 단계를 특정 |

## 3. 구현 단계

각 단계의 "완료"는 빌드가 아니라 **외부 왕복 또는 오류 유도 확인**이다.

### M0. 사전 준비 (코드 없음)
- **Ollama 설치**(현재 미설치) 후 모델을 받고 `ollama list`로 확정한 ID를 설정에 쓴다. 추측 금지. 기본 후보 `qwen2.5:7b`, 장비에 무거우면 `qwen2.5:3b`(ARCHITECTURE §4). **모델 ID·digest·양자화·Ollama 버전·장비 사양을 이 시점에 기록**해 A12를 조기 충족한다.
- Ollama 직접 호출 검증: `curl localhost:11434/v1/chat/completions` 로 응답 확인.
- Slack 앱 생성: 스코프 `app_mentions:read`, `chat:write`, 이벤트 구독 `app_mention`, 설치 후 Bot Token·Signing Secret 확보. 봇을 테스트 채널에 초대.
- `.env`(비커밋), `.env.example`(키 이름만) 커밋. `.gitignore`·`git init`·GitHub 저장소 생성은 완료(§6).
- **ngrok 장기 요청 실측**: 60초 지연 응답을 내는 임시 엔드포인트로 ngrok이 요청을 몇 초까지 유지하는지 1회 측정한다. 걸리면 처리 예산을 조정하고 PRD §5와 함께 갱신한다(엣지 504가 Slack 재전송으로 오인되면 P0-7 관측이 오염된다).
- **완료**: curl로 Ollama 실응답 확인, 토큰·시크릿 보유, ngrok 한계 측정값 기록.

### M1. 프로젝트 골격
- `build.gradle`, `settings.gradle`, Gradle Wrapper 8.14.3, Java 21 toolchain, Boot 3.4.1.
- 의존성: `starter-web`, `starter-validation`, `configuration-processor`, Lombok(`compileOnly`+`annotationProcessor`), `starter-test`, `junit-platform-launcher` (ARCHITECTURE §7).
- 패키지 루트 `com.slack.lab` 아래 `slack/`, `event/`, `llm/`, `config/`. (1단계는 Slack SDK 없이 HTTP 클라이언트로 호출하므로 SDK의 `com.slack.api`와 충돌하지 않는다. SDK를 도입하면 재검토)
- 설정 `record` + `@ConfigurationProperties` + `@ConfigurationPropertiesScan`. **키를 확정**한다:
  - `slack.signing-secret`, `slack.bot-token`, `slack.base-url`(기본 `https://slack.com/api`, A10 스텁용), `slack.send-deadline-ms=10000`
  - `llm.client=ollama|echo`(`@Bean` 안 분기), `llm.base-url`, `llm.model`, `llm.max-tokens`, `llm.keep-alive`, `llm.deadline-ms=50000`, `llm.connect-timeout-ms=3000`, `llm.verify-model-on-startup=true`
  - `processing.total-deadline-ms=60000`
  - `experiment.slow-mode-ms=0`, `experiment.dedup-enabled=true`
- 필수 설정 누락 시 기동 실패 (A3). `/health`.
- **완료**: `bootRun` 후 `/health` 200, 시크릿 없이 기동 시 실패.

### M1.5. 기한 강제 스파이크 (계획 최대 리스크를 앞당김, 타임박스 4시간)
- 필요한 것은 JVM과 스텁뿐이라 Ollama·Slack 없이 수행한다. 스텁 3종: JDK `com.sun.net.httpserver.HttpServer` 또는 `nc -l`로 (1) 헤더만 보내고 정지 (2) 본문 중간 절단 후 정지 (3) accept 후 무응답.
- 후보와 판정은 §7 참조. 성공 기준: 3종 모두에서 취소 후 **소켓이 실제로 닫히고** 남은 기한 안에 호출자가 실패를 받는다. `HttpRequest.timeout`이 헤더까지만 덮는지 본문까지 덮는지는 **단언하지 말고 이 스파이크의 1순위 측정 대상**으로 둔다.
- **Fallback**: 두 후보 모두 진행 중 요청을 끊지 못하면 소켓/스트림 강제 close를 최소 요구로 삼고, 그래도 안 되면 한계를 `EXPERIMENT-LOG.md`에 기록한다. 인터럽트만 보내고 방치하는 구현은 금지(ARCHITECTURE §4.1).
- 결론은 M4·M5 **양쪽 전송 계층에 동일하게 적용**한다(타임아웃 의미를 한 번만 학습).
- **완료**: 스텁 3종 결과표와 채택 방식이 `docs/EXPERIMENT-LOG.md`에 기록됨.

### M2. 서명 검증 + 수신 컨트롤러 (P0-1·2)
- `SlackSignatureVerifier`: `v0:{ts}:{rawBody}` HMAC-SHA256, `MessageDigest.isEqual`, 5분 초과 거부.
- `SlackEventController`: `@RequestBody String`(raw) → 검증 → 그 후 파싱. 401/400/`url_verification` 분기 (ARCHITECTURE §3.1).
- 재전송 헤더 `X-Slack-Retry-Num`·`X-Slack-Retry-Reason`을 첫 줄에서 로그.
- 테스트: 검증기 단위(정상·변조·시간 초과·빈 시크릿), 컨트롤러 슬라이스(401/400/challenge).
- **완료**: ngrok URL을 Slack Request URL에 등록해 **Verified** 표시 (실제 Slack 서명으로 최초 검증 — 프로토타입에서 미확인이었던 항목).

### M3. 값 객체 + 중복 억제 (P0-3·8)
- `SlackMessageEvent`: event_id, channel, user, text, ts, thread_ts, bot_id, subtype. `shouldIgnore()` 로 봇/subtype 차단. 답글 스레드 = `thread_ts ?? ts`. 프롬프트에서 `<@봇ID>` 멘션 토큰 제거.
- `EventDeduplicator`(인메모리): 원자적 선점(`ConcurrentHashMap.compute`), 상태 `PROCESSING/SENDING/COMPLETED/FAILED/UNKNOWN`, `attempt_id`.
  - 전이 API는 `transition(eventId, attemptId, from, to)` **CAS**로 고정한다. 소유자(`attempt_id`)가 아니면 거절한다.
  - `FAILED`→새 `PROCESSING` 재선점 시 새 `attempt_id`가 이전 소유자의 지연 쓰기를 무효화한다.
  - `COMPLETED`·`UNKNOWN` 10분 유지, **`FAILED`도 축출 규칙을 둔다**(맵 무한 증가 방지). 실행 중 엔트리는 TTL 청소 제외 (ARCHITECTURE §3.2). `get` 후 `put` 금지.
  - `experiment.dedup-enabled=false`이면 선점을 건너뛴다(M8의 중복 답글 관측용 실험 스위치, slow-mode와 같은 성격).
- 핸들러에 노출하는 것은 저장소가 아니라 **`AttemptHandle`**(`markSending/markCompleted/markFailed/markUnknown`)이다.
- 테스트: 동시 N회 선점 시 1승(A9), 소유자 아닌 전이 거부, TTL 경계, 청소가 PROCESSING을 지우지 않음, FAILED 재선점.
- **완료**: 단위 테스트 통과. (외부 왕복은 M6에서 확인)

### M4. LLM 클라이언트 (P0-4)
- `LlmClient` 인터페이스(호출 시 **남은 기한**을 받음), `OpenAiCompatibleLlmClient`(`choices[0].message.content`, `max_tokens`, `keep_alive`, 시스템 프롬프트로 길이 제한), `EchoLlmClient`. 전환은 `llm.client`.
- 전송 계층은 **M1.5 결론을 따른다.** 연결 제한은 클라이언트 단위이므로 `llm.connect-timeout-ms=3000` 고정이다(호출별 `min(3s, 남은 시간)`은 표현하지 않는다).
- 기동 시 모델 존재 확인은 **fail-fast**(pitfall 8): 존재하지 않는 모델 ID면 원인이 분명한 오류로 기동 실패. 우회는 `llm.verify-model-on-startup=false`(A6 유도용). 워밍업 1회.
- **완료**: 실제 Ollama 호출 성공(응답·소요 시간 로그). 잘못된 모델 ID로 기동하면 실패.

### M5. Slack 발신 클라이언트 (P0-5)
- `SlackClient.postMessage(channel, thread_ts, text, deadline)`; 본문 `ok` 확인, `ok:false` 오류 코드 분류. `slack.base-url` 사용.
- 결과 3분류: **성공(ts 확인) / 명확한 실패(전송 안 됨 확실) / 결과 불명**(요청 후 연결 끊김·읽기 타임아웃). 예외를 밖으로 던지지 않고 결과 타입으로 반환.
- 기한 `min(발신 시작+slack.send-deadline-ms, t0+processing.total-deadline-ms)`, 남은 시간 없으면 발신 시작 안 함(A15). 전송 계층은 M1.5 결론과 동일.
- **완료**: 실제 채널에 스레드 답글 1건, `ok:false` 유도(잘못된 채널) 시 실패로 분류되고 로그.

### M6. 핸들러 + 흐름 조립 (P0-6, 전체 왕복)
- `SlackEventHandler`: HTTP 무지식. 입력 = 이벤트 + `AttemptHandle`(attempt_id, t0 포함), 출력 = 결과 타입(성공/명확한 실패/결과 불명). `markSending` 성공 후에만 발신, 기록 실패 시 발신 금지.
- 흐름: LLM(≤`llm.deadline-ms`) → 성공이면 답변, 실패·기한 초과면 **실패 안내 1회** → `COMPLETED/FAILED/UNKNOWN`. 답변 발신 실패 뒤 안내 연쇄 발신 금지. 늦은 LLM 결과 폐기.
- 컨트롤러: 결과 → HTTP 응답 매핑 (ARCHITECTURE §3.1 표), 처리 권한 확보 후 예외는 200 유지·로그, 선점 전 내부 오류만 500.
- **응답 쓰기 실패**(3초 뒤 Slack이 이미 연결을 끊은 경우 등)는 `ack_delivered=false`로 로그에만 남기고 **dedup 상태를 바꾸지 않는다**(핸들러가 이미 상태를 확정한 뒤다).
- `experiment.slow-mode-ms` 지연 스위치: LLM 예산에 포함 (ADR-7).
- **`// 2단계에서 여기가 바뀐다` 주석만** 남기고 큐 코드는 넣지 않는다.
- **완료**: A1(실제 멘션 → 스레드 답글), A6·A8·A10·A15·A16 오류 유도 확인.

### M7. 계측 (P0-7, 규칙 7)
- 로그 필드: event_id, attempt_id, 단계, 결과, 소요(단조 시계), 최초 수신 시각, 재전송 헤더, `ack_delivered`, 종류(답변/실패 안내). 프롬프트·본문·토큰·시크릿은 로그 금지.
- 집계할 값: 재전송 횟수 / 중복 억제 수 / 중복 답글 수 / LLM 소요 시간 (답변과 실패 안내 분리). 집계는 로그 grep 수준으로 충분(지표 라이브러리 도입 금지).

### M8. 실험 + 문서 (P0-7 산출물)
1. 환경 기록 확인(M0에서 확보한 값, A12).
2. **경계 실험**: `llm.client=echo` 고정, `slow-mode` 2.5s / 3.0s / 5s / 30s 를 **각 5회 반복**. 재전송 발생 여부·횟수(관측값, 추정 금지)·중복 억제·중복 답글 수·`ack_delivered`. 실제 모델은 쓰지 않는다(예산 소진과 재전송 경계가 섞이지 않게).
3. **실제 LLM 실험**(별도 표): slow-mode 0, 실제 Ollama 지연으로 동일 관측. 콜드 스타트는 분리 기록.
4. **중복 답글 관측 배치**(1회): `experiment.dedup-enabled=false`로 실제 중복 답글을 만들어 dedup on/off 대비표를 남긴다("왜 큐가 필요한가"의 근거).
5. 선택 배치(1회): `llm.deadline-ms`·`processing.total-deadline-ms`를 늘려 50초 하드 캡이 가리는 현상(90초 뒤 답변 + 그사이 재전송) 관측.
6. 오류 유도 매트릭스: A2·A5·A6·A7·A8·A10·A15·A16.
7. **실험 체크리스트**: ngrok URL 재등록, 반복 타임아웃으로 Slack이 이벤트 구독을 자동 비활성화하지 않았는지 배치 사이 확인·재활성화, 배치 간 간격.
8. 갱신: `docs/EXPERIMENT-LOG.md` 작성(채널 ID·event_id 마스킹), `AGENTS.md` 현재 단계 줄, 구조가 바뀐 부분은 `ARCHITECTURE.md` (규칙 9). P0 실측으로 PRD §8 "7B로 충분한가"·P1 성능 목표 달성 가능성 판단.
- **완료**: PRD §6 1단계 체크박스 3개를 실측 데이터로 채움.

## 4. 리스크와 대응

| 리스크 | 대응 |
|---|---|
| 전체 기한이 안 지켜짐 (RestClient read timeout 등) | M1.5 스파이크(스텁 3종, 타임박스 4h), 소켓 실제 종료로 판정, fallback 정의 |
| 60초는 **워치독이 아니라 호출별 기한의 합**으로만 강제됨 (동기 단일 스레드에 전역 인터럽트 없음) | A16으로 총 소요 검증. ARCHITECTURE §4.1의 "애플리케이션 처리 예산" 문구와 일치 |
| 핸들러가 `SENDING`을 기록해야 해서 상태 관리를 알게 됨 | 핸들러는 `AttemptHandle`만 받고 저장소를 모른다. HTTP 타입 금지(A13). P1에서 저장소만 교체 |
| ngrok이 장기 요청을 끊어 재전송으로 오인됨 | M0에서 60초 지연 실측, 필요 시 예산 조정 + PRD §5 갱신 |
| 3초 뒤 Slack이 연결을 끊어 응답 쓰기가 실패 | `ack_delivered=false`로만 기록, 상태 불변. "왜 큐가 필요한가"의 증거로 활용 |
| 반복 타임아웃으로 Slack이 이벤트 구독을 자동 비활성화 | 배치 사이 구독 상태 확인·재활성화 절차 (M8-7) |
| 50초 하드 캡·dedup이 P0-7의 관측 대상 현상을 가림 | 두 값을 설정으로 노출하고 M8-4·5에 해제 배치 추가 (코드 추가 없음) |
| 스코프 추가 후 재설치 누락 | M0에서 스코프 확정 후 설치, 변경 시 재설치 |
| 동기 처리 중 재전송이 동시에 도착 | 선점 실패 → 200 즉시 반환(A9). 톰캣 스레드 점유는 관측 항목으로 기록 |
| Ollama 취소가 내부 추론 종료를 보장하지 않음 | 가정하지 않고 잔여 추론의 영향을 별도 관측 (ARCHITECTURE §4.1) |
| 7B 모델이 장비에 과중 | `qwen2.5:3b` 폴백 (M0) |
| 재전송 없이 발신 실패 시 답글이 영영 없음 | P0의 명시적 한계로 EXPERIMENT-LOG에 기록 (자동 복구는 P1) |
| "빌드 통과 = 완료"로 착각 (프로토타입 재현) | 단계별 완료 조건을 외부 왕복으로 고정 |

## 5. 검증 요약

- 단위: 서명 검증기, dedup 상태 전이·동시성·CAS, `shouldIgnore`, 결과 분류.
- 슬라이스: 컨트롤러 401/400/challenge/무시/처리.
- 스파이크: 스텁 3종에서 취소 후 소켓 종료(M1.5).
- 외부 왕복(수동): Request URL Verified → 실제 멘션 → 스레드 답글.
- 오류 유도: 잘못된 서명, Ollama 중단, 런타임 잘못된 모델 ID, 지연·조각·정체 응답, `ok:false`, 발신 중 연결 끊김, 예산 소진.
- 관측: 재전송 헤더·중복 억제·중복 답글·LLM 시간·`ack_delivered` 로그 → 실험 기록.

## 6. 확정된 결정

| # | 결정 | 내용 |
|---|---|---|
| 1 | 저장소 | `git init` 완료(`main`). GitHub `slack-lab` **public** (`origin` 연결 완료, 커밋·push 전). `.gitignore`는 `.env*`·`.idea/`·빌드 산출물·로그·`.omc/` 런타임 상태를 제외하고 **`.claude/docs`는 커밋**한다(멘토가 AI 활용 방식을 볼 수 있게) |
| 2 | 패키지 루트 | `com.slack.lab` — 실명 등 개인정보를 코드·저장소에 넣지 않는다 |
| 3 | LLM | **Ollama 로컬 추론 확정.** 무료 API 티어(Groq·Gemini 등)는 대화가 외부로 나가고(PRD §5 데이터), 요청 한도가 걸려 3초 초과 실험의 재현성이 흔들린다. 모델 ID는 **Ollama 설치 후 `ollama list`로 확정**(현재 이 머신에는 미설치) |
| 4 | 문서 위치 | `.claude/docs/PLAN.md`가 정본(PRD·ARCHITECTURE와 같은 폴더), 루트 `PLAN.md`는 없다. CLAUDE.md의 세 링크(PRD·ARCHITECTURE·PLAN)와 PRD·ARCHITECTURE 내 PLAN 참조를 모두 실제 경로로 교정 완료 |

### 공개 저장소 규칙
- 커밋 전 `git diff --cached`로 토큰·시크릿·실명·로컬 절대경로가 없는지 확인한다.
- Slack 채널 ID·event_id 원본은 `EXPERIMENT-LOG.md`에 그대로 쓰지 않고 마스킹한다.
- git 사용자 정보는 GitHub noreply 주소를 쓴다(이미 설정됨).

## 7. RALPLAN-DR 요약 (short mode)

**Principles**
1. **관찰이 산출물이다.** P0-7(3초 초과 재현·계측)이 목적이고 나머지는 관찰 장치다.
2. **완료 = 외부 왕복 성공 + 오류 유도 확인.** 빌드 통과는 완료가 아니다.
3. **단계를 앞지르지 않는다.** 큐·Redis·재시도 프레임워크를 P0에 넣지 않고 주석으로만 남긴다.
4. **실패는 조용히 사라지지 않는다.** 명확한 실패와 결과 불명을 구분하고, 결과 불명은 자동 재발신하지 않는다.
5. **핸들러는 HTTP를 모른다.** 저장소가 아니라 `AttemptHandle`만 받는다.

**Decision Drivers (상위 3)**
1. 처리 예산(LLM 50초 + 발신 10초, 총 60초)을 **실제로 강제**할 수 있는가 — 진행 중 요청 취소, 늦은 결과 폐기
2. 재전송·중복을 **정확히 관찰**할 수 있는가 — 재현 가능한 고정 지연과 event_id 단위 로그, 그리고 예산·dedup이 관측을 가리지 않는가
3. 2단계 이행 시 핸들러를 **HTTP 의존 없이** 옮길 수 있는가 (결과 타입 확장은 허용, ARCHITECTURE §2·§5.1)

**Viable Options — 호출 기한 강제 방식 (M1.5 스파이크가 고름)**

| | A1: `RestClient` over `JdkClientHttpRequestFactory` | A2: `HttpClient.sendAsync` 직접 호출 + `future.cancel(true)` | B: Apache HttpClient5 `RestClient` + 응답 타임아웃 + 별도 워치독 |
|---|---|---|---|
| 장점 | 기존 `RestClient` 스택 유지 | 요청별 `HttpRequest.timeout`과 취소 가능. 추가 의존성 없음 | 소켓·연결 타임아웃이 세분화됨 |
| 단점 | 타임아웃이 팩토리/클라이언트 단위라 **호출별 남은 기한 요구를 만족 못 함 → 즉시 제외** | 본문 수신 구간 타임아웃 범위는 스파이크로 확인 필요. 취소는 best-effort. 파생 스테이지의 `orTimeout`은 진행 중 요청을 끊지 못하므로 사용 금지 | 의존성 추가, 워치독·취소 경로 직접 관리 |

- **Option C (스레드 인터럽트 후 방치)** 는 ARCHITECTURE §4.1이 금지하므로 제외. 블로킹 `send()`는 외부에서 취소할 수 없어 `sendAsync`로 고정한다.
- 기본 가설은 A2, 스파이크 결과로 A2/B 중 확정한다. `HttpClient`의 connect timeout은 클라이언트 단위라 연결 제한은 3초 고정.

**Viable Options — SENDING 기록 주체**

| | Option 1 (채택): 핸들러가 `AttemptHandle`로 전이 | Option 2: 핸들러는 결과만 반환, 컨트롤러가 전이 | Option 3: 핸들러가 `beforeSend` 게이트 콜백을 주입받음 |
|---|---|---|---|
| 장점 | 발신 **직전** `SENDING` 기록(§3.2 요구). P1 워커가 같은 핸들러 재사용. 저장소를 모른다 | 핸들러가 상태를 전혀 모른다 | 저장소·핸들 모두 모른다 |
| 단점 | 핸들 인터페이스 하나 추가 | 발신 직전 기록이 불가능해 §3.2 불변식 위반 → **기각** | 상태 기계를 콜백 뒤에 숨겨 추적이 어려움. ARCHITECTURE §2가 이미 인터페이스 의존을 허용하므로 이득이 없음 → **검토 후 기각** |

## 8. ADR

- **Decision**: 1단계를 M0→M1→M1.5→M2…M8로 진행한다. 처리 예산 60초(LLM 50초·발신 10초)는 PRD §5 요구로 유지하되 설정값으로 노출한다. 호출 기한은 M1.5 스파이크로 A2/B 중 확정한다. 핸들러는 `AttemptHandle`만 받는다. 실험 배치에 예산·dedup 해제 배치를 추가한다.
- **Drivers**: 예산의 실제 강제 가능성 / 재전송·중복의 정확한 관측 / 2단계 이행 시 HTTP 무의존.
- **Alternatives considered**: `RestClient` 유지(A1, 호출별 기한 불가), Apache 기반(B, 의존성·워치독 비용), 인터럽트 방치(C, 금지), 컨트롤러 전이(Option 2, 불변식 위반), 콜백 주입(Option 3, 간접화 비용), 60초 예산 폐기(Architect antithesis — PRD §5 요구이자 문서화된 비기능 요건이라 유지하고 설정으로 해제 가능하게 함).
- **Why chosen**: 최대 리스크인 기한 강제를 M1.5로 앞당겨 후속 마일스톤의 전송 계층 결정을 미리 확정하고, 예산·dedup을 설정 스위치로 둬 코드 추가 없이 P0-7의 관측 충실도와 PRD의 상한을 함께 얻는다.
- **Consequences**: 스파이크에 4시간 타임박스가 생긴다. 단일 스레드 동기 흐름이라 60초는 호출별 기한의 합으로만 강제되며(전역 워치독 없음), 그 한계를 A16과 리스크 표에 명시했다. 실험 설정 키가 늘어난다(`llm.client`, `experiment.dedup-enabled` 등).
- **Follow-ups**: (1) M0에서 ngrok 60초 실측 후 예산 조정 여부 판단 (2) M8-5 결과로 50초 하드 캡이 학습 목표를 가리는지 보고 PRD §5 재검토 (3) P1 착수 전 `AttemptHandle`을 `ProcessingStateStore` 위로 이식 (4) 스파이크 결과표를 `EXPERIMENT-LOG.md`에 기록.

## 9. Changelog (Consensus 1회전에서 반영한 개선안)

- **Critic BLOCKER**: A6 유도 방식을 (a) Ollama 중단 (b) 검증 우회 프로파일로 재정의, `llm.verify-model-on-startup` 추가. M8 경계 실험을 Echo 고정·각 5회로 구체화하고 실제 LLM 실험을 별도 표로 분리.
- **Critic MAJOR**: `slack.base-url` 추가(A10 실행 가능), A5 판정을 curl 기준으로 변경, 핸들러 의존을 `AttemptHandle`로 확정하고 Driver 3 재서술, 스파이크 스텁·타임박스·fallback 명시, A15·A16 추가, ngrok 실측을 M0에 추가.
- **Architect MAJOR**: Option A를 A1/A2로 분리(A1 제외), `sendAsync`+`cancel(true)` 및 소켓 종료 판정으로 정정, 예산 소진 경로(A15)와 총 60초(A16) 검증 추가, 3초 뒤 연결 단절 대응(`ack_delivered`), dedup 해제 배치, 스파이크를 M1.5로 이동.
- **MINOR**: FAILED 축출·CAS 전이, A13 grep 범위 확장·경로 고정, 설정 키 확정(`llm.client` 등), 폴백 모델, Slack 구독 자동 비활성화 체크리스트, 정본 `.claude/docs/PLAN.md` 동기화·문서 링크 교정.
- **미반영(사유)**: Architect의 "예산 사실상 무한 배치"는 선택 배치(M8-5)로 격하해 반영. Option 3(콜백)은 기각 근거만 §7에 기록.
