# EXPERIMENT-LOG

실측값과 실험 결과를 남긴다. 추정값은 적지 않고, 표본 수와 조건을 함께 적는다.
공개 저장소이므로 채널 ID·event_id 원본은 마스킹한다.

## 1. 검증 환경 (PRD §5, A12)

기록일: 2026-09-19 (M0)

| 항목 | 값 |
|---|---|
| 장비 | Apple M1, RAM 16GB, arm64 |
| OS | macOS 26.6.1 |
| Java | OpenJDK 21.0.9 |
| Ollama | 0.34.0 (Homebrew formula, 기본 설정 — 환경변수 미설정, `ollama serve` 수동 실행) |
| 모델 ID | `qwen2.5:7b` |
| digest | `845dbda0ea48ed749caafd9e6037047aa19acfcfd82e704d7ca97d631a0b697e` |
| 파라미터 / 양자화 | 7.6B / Q4_K_M (4.7GB) |
| 실행 프로세서 / 컨텍스트 | 100% GPU / 4096 (`ollama ps`) |
| `keep_alive` | 기본값(5분). M4에서 `llm.keep-alive` 확정 |
| 출력 토큰 상한 | 미정 (M4에서 `llm.max-tokens` 확정) |
| 추론 동시성 | 미측정 |

## 2. M0 관측

### 2.1 Ollama 직접 호출 (Slack·앱 코드 없이 curl)

- 엔드포인트: `POST /v1/chat/completions` (OpenAI 호환), 응답 경로 `choices[0].message.content` 확인.
- `GET /v1/models` 응답에 `qwen2.5:7b` 포함 → M4의 기동 시 모델 존재 확인에 사용 가능.
- 조건: 시스템 프롬프트 "한국어로 한 문장으로만 답한다.", 질문 "커넥션 풀 고갈이 뭐야?", `max_tokens=80`, 동일 질문 3회.

| 호출 | 조건 | 총 소요 | completion_tokens |
|---|---|---|---|
| 1 | 콜드 (모델 적재 포함) | 14.0s | 76 |
| 2 | 워밍 | 5.3s | 미기록 |
| 3 | 워밍 | 5.4s | 미기록 |

- **표본 3건의 참고치**이며 본실험(M8)의 근거로 쓰지 않는다.
- 워밍 후에도 3초를 넘는다 → 인위적 지연 없이도 Slack 3초 마감 초과를 재현할 수 있을 것으로 보이나, **재전송 관측은 M8에서 확인한다** (미검증).

### 2.2 품질 관찰 (1건, 원인 미확인)

- 1회차 답변 중간에 중국어 문장이 섞였고, 앞부분 설명도 부정확했다.
- 재현 여부·원인(`max_tokens`, 시스템 프롬프트 길이 등)은 미확인이다.
- 후속: M4에서 시스템 프롬프트에 "한국어로만 답한다"를 명시하고 재현을 확인한다. 계속 재현되면 `exaone3.5:7.8b`를 시험한다 (PRD §8 "7B로 충분한가"의 실측 근거).

### 2.3 ngrok

- ngrok 3.39.11, 무료 플랜, authtoken 등록 후 `ngrok http 8080` (2026-09-20).
- 조건: 로컬 임시 서버가 `GET /{초}` 요청에 해당 초만큼 지연 후 200을 응답한다. 터널 URL로 `curl`(ngrok-skip-browser-warning 헤더)을 1회씩 호출했다.

| 지연 | HTTP | 총 소요 |
|---|---|---|
| 5초 (사전 확인) | 200 | 5.28초 |
| 60초 | 200 | 60.30초 |

- 결론: 60초 지연 요청이 엣지에서 끊기지 않고 정상 응답됐다. **P0 처리 상한 60초(PRD §5)는 조정하지 않는다.**
- 한계: 각 1회 측정이며 60초 초과 구간은 재지 않았다. 처리 예산이 60초를 넘도록 바꾸게 되면 다시 측정한다.

## 2.4 M1 골격 검증 (2026-09-21)

조건: Java 21.0.9, Gradle 8.14.3, Boot 3.4.1, `.env` 로드(값 미기록).

| 확인 | 방법 | 결과 |
|---|---|---|
| 정상 기동 | `.env` 로드 후 `./gradlew bootRun`, `curl localhost:8080/health` | HTTP 200, `{"status":"UP"}` |
| 필수 값 누락(A3) | `SLACK_SIGNING_SECRET`만 제거 후 `bootRun` | 기동 실패, `Property: slack.signingSecret` `Reason: 공백일 수 없습니다` 출력, 8080 미응답 |
| 필수 값 누락 | 네 키 모두 제거 후 `bootRun` | 기동 실패, 첫 실패 빈(`llm.model`)만 보고됨 — 한 번에 하나씩 나온다 |
| 빌드 | `./gradlew build` | 통과 (바인딩·`/health` 테스트 4건) |

## 2.5 M2 서명 검증·수신 컨트롤러 검증 (2026-09-22)

조건: `.env` 로드 후 `bootRun`, 로컬 curl(서명은 `openssl dgst -sha256 -hmac`으로 생성). Slack 실제 서명 검증(Request URL Verified)은 사람 확인 지점으로 남아 있다.

| 확인 | 결과 |
|---|---|
| 유효 서명 `url_verification` (A4) | 200, `{"challenge":"abc"}` |
| 잘못된 서명 (A2) | 401 |
| 서명은 맞고 타임스탬프 400초 전 (A2) | 401 |
| 서명 유효 + 본문 깨짐 (A4) | 400 |
| 재전송 헤더 `X-Slack-Retry-Num: 1`·`Reason: http_timeout` | 서명 실패(401)여도 로그 첫 줄에 `retry_num=1 retry_reason=http_timeout` 기록 |
| 단위·슬라이스 테스트 | 검증기 7건(정상·변조·시간 경계 ±300초·헤더 누락·빈 시크릿), 컨트롤러 6건 통과 |

## 2.6 M4 LLM 클라이언트 검증 (2026-09-22)

조건: `.env` 로드 후 `bootRun`, Ollama `qwen2.5:7b` 로컬 기동 중.

| 확인 | 방법 | 결과 |
|---|---|---|
| 기동 시 모델 확인 + 웜업 | 정상 `.env`로 `bootRun` | `LLM 모델 확인됨 model=qwen2.5:7b` 로그, 이어서 `LLM 호출 성공 elapsed_ms=765`(애플리케이션 코드 경유, curl 아님) |
| 잘못된 모델 ID (A6 경로 (b) 준비) | `LLM_MODEL=not-a-real-model`로 `bootRun` | `IllegalStateException: llm.model=not-a-real-model ... 에 없음`, `BUILD FAILED`, `/health` 미기동(000) |
| 검증 우회 | 위와 동일 모델 + `--llm.verify-model-on-startup=false` | 기동 성공(`/health` 200), 웜업 호출은 실제로 시도되고 `status=404`로 명확히 실패 기록(`LLM 호출 실패 status=404`) — M6에서 이 상태로 A6(b) 실패 안내 유도 |
| 취소 재확인 | 단위 테스트: 무응답 스텁 + 기한 500ms | `TimedOut` 반환, 경과 3초 미만(스텁의 sleep 30초까지 기다리지 않음 — M1.5 A2 결론 재확인) |
| 단위 테스트 | `./gradlew build` | LLM 신규 8건(OpenAiCompatible 6, Echo 2) 포함 전체 통과 |

## 2.7 M5 Slack 발신 클라이언트 검증 (2026-09-22)

조건: `.env` 로드, 실제 Slack 워크스페이스(테스트 채널, ID는 마스킹). curl이 아니라 `SlackClient`(애플리케이션 코드)로 호출.

| 확인 | 방법 | 결과 |
|---|---|---|
| 스코프 부족 재현 | `chat:write` 없는 토큰으로 발신 | `ok:false` → `Failed(reason=missing_scope)`로 정확히 분류(예외 누출 없음) |
| 스코프 추가 후 재설치 | Slack 앱에 `chat:write` 추가 + Reinstall (사람 조작) | 토큰 값 불변, `x-oauth-scopes`에 `chat:write` 추가 확인 |
| 채널 미초대 재현 | 봇이 채널에 없는 상태로 발신 | `Failed(reason=not_in_channel)` |
| 봇 채널 초대 (사람 조작) | 테스트 채널에 `/invite` | 이후 발신 성공 |
| **실제 스레드 답글 (A1 전신)** | `SlackClientManualIT`(수동 통합 테스트, `SLACK_MANUAL_TEST_CHANNEL` 플래그로만 실행) | `Success(ts=...)` — 애플리케이션 코드 경로로 실제 채널에 메시지 도달 확인 |
| 잘못된 채널 (`ok:false`) | 존재하지 않는 채널 ID로 발신 | `Failed(reason=channel_not_found)` |
| 취소 재확인 | 단위 테스트: 무응답 스텁 + 기한 500ms | `Unknown` 반환, 경과 3초 미만(M1.5 A2 결론 재확인) |
| 연결 자체 실패 | 존재하지 않는 포트로 발신 | `Failed(reason=connect_failed:...)` — 결과 불명이 아니라 명확한 실패로 분류(연결도 안 됐으므로) |
| 예산 소진 | `remainingMs=0`으로 호출 | 네트워크 호출 없이 즉시 `Failed(reason=budget_exhausted)` |
| 단위 테스트 | `./gradlew build` | Slack 신규 7건(interrupt 취소 회귀 포함) 포함 전체 통과. `SlackClientManualIT` 2건은 플래그 없으면 스킵(평소 빌드는 실제 API를 부르지 않음) |

## 2.8 M4/M5 codex critic 리뷰 (2026-09-22)

`omc ask` 대신 `codex exec --sandbox read-only`를 critic으로 직접 호출(1차 시도는 프롬프트에 포함된 백틱이 셸에서
명령 치환으로 해석돼 멈춤 — stdin으로 프롬프트를 넘기는 방식으로 교체해 해결).

**1차 리뷰(M4)**: REQUEST CHANGES. 발견 4건 — (1) 파싱 예외가 `Failed`로 감싸이지 않고 누출 (2) `content` 필드
누락·빈 값을 성공으로 처리 (3) `InterruptedException` 경로에서 `future.cancel(true)` 누락 (4) `/models` 확인이
동기 `send()`+`request.timeout`만 써서 본문 정체 시 기동이 무기한 대기 가능. 모두 수정: `execute()` 헬퍼로
sendAsync+cancel(true) 패턴을 채팅·모델확인 공통화, `parseContentSafely`로 파싱 실패·빈 응답을 모두 `Failed`로.
같은 결함(3)이 `SlackClient`에도 있어 함께 수정.

**2차 리뷰**: 코드 수정 4건 모두 승인. 다만 회귀 테스트 2건이 결함을 실제로 검출하지 못함을 지적(헤더 없이 멈추는
스텁은 `request.timeout`만으로도 통과, 인터럽트 테스트는 스레드 종료만 확인). 헤더는 보내고 본문에서 정지하며
서버가 소켓 종료를 직접 감지하는 스텁(`chatHeadersThenHangs`/`modelsHeadersThenHang`/`headersThenHangs`, M1.5와
동일한 방식)으로 교체.

**변이 검증 결과(한계, 정직하게 기록)**: `future.cancel(true)` 호출을 실제로 제거해 인터럽트 테스트가 잡아내는지
확인했다. 이 JDK 21 `HttpClient`는 명시적 취소 없이도 인터럽트 후 약 4.1초 뒤 소켓이 닫혀, 이 테스트는 그 결함을
완전히 구분하지 못했다(원인 미상 — 추정하지 않음). `future.cancel(true)`는 API 계약상 맞는 코드라 유지하되,
이 특정 회귀 테스트의 검출력 한계를 그대로 남긴다.

**codex 세션 소진**: GPT-5h 세션이 소진돼 M6부터는 ralph 지침의 폴백대로 codex 대신 oh-my-claudecode code-reviewer를 critic으로 쓴다. 필요하면 codex 세션 복구 후 재검토한다.

## 2.9 M6 실제 멘션 왕복 (A1, 2026-09-23)

**막힘 원인과 해결**: 처음엔 Event Subscriptions에 app_mention을 추가·저장하고 Enable Events도 켜져 있었는데도 ngrok에 이벤트가 전혀 도착하지 않았다.
원인은 Slack 앱의 **Socket Mode가 켜져 있었던 것** — Socket Mode가 활성화되면 Request URL이 Verified여도 실제 이벤트는 WebSocket으로만 전달되고 HTTP로는 안 온다.
Socket Mode를 끄고 Request URL을 지웠다가 다시 등록(재검증)한 뒤에야 이벤트가 도착했다.

| 확인 | 결과 |
|---|---|
| A1: 실제 멘션 → 스레드 답글 | 성공. 콜드 스타트 첫 시도 elapsed_ms=19600(LLM), 이후 elapsed_ms=5209로 정상화 |
| 재전송 관측 | Slack이 3초 타임아웃으로 `retry_num=1 retry_reason=http_timeout` 재전송 — dedup이 정확히 `중복 억제`로 막음(P0-7 목표 현상 실측) |
| ack_delivered | 정상 처리·중복 억제 양쪽 다 `ack_delivered=true` 로그 확인 |
| LLM 언어 혼용 | qwen2.5:7b가 느슨한 프롬프트에서 중국어·영어를 섞어 답한 사례 발견. 시스템 프롬프트 강화("한국어로만", 혼용 금지 명시) + `temperature=0.3` 추가로 해결, 재검증 완료 |

**미해결(당시)**: code-reviewer(codex 세션 소진으로 대체) 리뷰에서 HIGH 1건 발견 — `SlackEventHandler.handle()`에 예외 가드가 없어 예상 못한 예외 시 dedup이 `PROCESSING`에 영구 고착한다(함정 1·3번). 아래 §2.10에서 해결.

## 2.10 M5 codex critic 2차 재검토 + M6 HIGH/MEDIUM 수정 (2026-09-23)

**codex 세션 복구 확인**: ping 요청에 정상 응답 — M4/M5 리뷰 당시 소진됐던 세션이 복구됐다. 이후 critic 요청은 codex로 재개.

**브랜치 분리**: `feature/m5-slack-client`에 섞여 있던 M5 codex 2차 수정(`SlackClient`·`OpenAiCompatibleLlmClient` 등)과 M6 신규 파일을 diff 단위로 분리했다.
M5 수정분만 커밋(`ea80c92`)해 PR #11에 반영 → codex critic 재검토 → **OKAY**(병합 차단 결함 없음, 종합 권고 COMMENT/WATCH) → 병합.
M6 파일은 `git stash`로 보관했다가 병합된 `develop`에서 새로 판 `feature/m6-handler`에 복원했다.

codex가 짚은 비차단 의견 2건(모두 병합 안 막음, P0 범위 밖으로 분류):
- MEDIUM: `SlackClient.postMessage`의 `sendAsync()` 제출과 취소 타이머 예약이 같은 `try` 안에 있어, 제출 성공 후 예약 자체가 실패하면 이미 나갔을 수 있는 요청을 `Failed`로 오분류할 수 있다(일반 실행 경로에서 발생 조건은 미확인).
- LOW: 같은 패턴이 `OpenAiCompatibleLlmClient`에도 있고, 두 클라이언트 모두 요청 준비 시간을 `remainingMs`에서 차감하지 않는다(이번 커밋 이전부터 있던 사항, 회귀 아님).

**M6 HIGH 버그 수정**: `SlackEventHandler.handle()` 전체를 try/catch로 감싸고, `markSending` 진입 여부를 `boolean[]` 플래그로 추적해
예외 발생 시 진입 전이면 `markFailed`, 진입 후면 `markUnknown`으로 종료 상태를 확정하도록 고쳤다. 같은 원리로
`OpenAiCompatibleLlmClient.buildRequest`의 직렬화 실패도 예외 대신 `LlmResult.Failed`를 반환하게 했다.

**M6 MEDIUM 4건**: LLM 예산을 `llm.deadline-ms`뿐 아니라 총 처리 기한에서 발신 몫(`slack.send-deadline-ms`)을 뺀 값으로도 clamp(A16),
`event_callback` 외 타입은 400 대신 200+무시로 변경(재전송 유발 방지), slow-mode 테스트를 `ArgumentCaptor`로 실제 차감된 `remainingMs` 값을 검증하도록 강화,
`AckLoggingFilter` 단위 테스트 5건 신규 작성 + 401/400 응답엔 `ack_delivered` 로그를 생략하도록 수정.

수정 후 `./gradlew build` 전체 통과 확인(기존 테스트 회귀 없음, 신규 테스트 포함).

## 2.11 M6 PR #12 최종 검토 2회전 (2026-09-23)

**codex usage limit**: PR #12(커밋 `21b7da2`)에 codex critic 재검토를 요청했으나 `ERROR: You've hit your usage limit`로 exit=1 실패 —
판정을 받지 못했다(§2.9의 "codex 세션 소진"과는 다른 원인). 계획대로 code-reviewer(대체)로 폴백.

**code-reviewer 2차 리뷰 결과 — REQUEST CHANGES (차단 1건)**:
- **[HIGH]** 이번 커밋의 핵심 수정인 `SlackEventHandler.handle()`의 예외 가드에 회귀 테스트가 0건이었다. 8건의 handler 테스트 중
  `thenThrow`로 예외를 주입하는 테스트가 하나도 없어, 지난 라운드에 고친 "예외 시 PROCESSING 영구 고착" 결함이 재발해도 빌드가 그대로 통과하는 상태였다(규칙 5 위반).
- **[MEDIUM]** slow-mode의 인위적 지연(sleep)이 LLM 호출과 다른 예산식을 써서, `llm.deadline-ms` 계산에만 추가한 총 기한 clamp가 sleep에는 적용되지 않았다.
  `processing.total-deadline-ms`를 줄이거나 `slow-mode-ms`를 크게 준 M8 실험 조합에서 A16을 넘길 수 있는 경로가 남아 있었다.
- **[MEDIUM]** `AckLoggingFilter`의 `ack_delivered` 생략 조건이 상태 코드 denylist(400·401)라, `url_verification` 200·무시된 type 200 등
  event_id가 애초에 없는 다른 200 응답 경로에서 `ack_delivered=true event_id=null`이 새고 있었다. M7의 로그 집계를 오염시키는 경로였다.
- 그 외 LOW 5건(SlackClient 워치독 +500ms, `EventDeduplicator.markFailed`가 SENDING 상태에서 항상 먼저 "전이 거절" WARN을 남기는 노이즈,
  handle() 종료 로그 자체에서 예외 나면 반환값이 실제와 어긋나는 경계, 필터 경로 비교의 컨텍스트 패스 취약성, `boolean[]` 대신 지역 변수로도 충분하다는 최적성 의견)과
  Open Question 1건(시계 소스 결합)은 병합 차단 아님 — P1 검토 대상으로만 PLAN에 남긴다.

**반영**: HIGH·MEDIUM 2건 모두 수정.
- 예외 주입 회귀 테스트 2건 추가(`markSending` 전/후 각각 `thenThrow` → `Failed`/`Unknown` + dedup 상태 확인).
- `llmBudgetMs(t0)` 헬퍼로 예산식을 하나로 합쳐 slow-mode sleep과 LLM 호출 양쪽에 동일하게 적용.
- `AckLoggingFilter`를 상태 코드 denylist 대신 `EVENT_ID_ATTR` 존재 여부(positive guard)로 전환 — 컨트롤러가 처리 대상으로 판단한 요청에만 로그가 남는다. 테스트도 상태 코드 스텁에서 event_id 유무 스텁으로 갱신.
- `ARCHITECTURE.md` §2에 `AckLoggingFilter`·`HandlingResult`(및 M3부터 누락돼 있던 `AttemptHandle`·`ClaimResult`·`ProcessingState`)를 추가(규칙 9).

수정 후 `./gradlew build` 재확인 통과(신규 회귀 테스트 포함, A13 재확인 0건).

## 3. 기한 강제 스파이크 (M1.5, 2026-09-21)

조건: Java 21.0.9 `java.net.http.HttpClient`(HTTP/1.1 고정, connect timeout 3s), 루프백 스텁, 기한 2초·관측 창 6초. Ollama·Slack 미사용.
재현: `java docs/spikes/DeadlineSpike.java` (약 1분). 2회 실행해 같은 결과를 얻었다.
판정: 클라이언트 예외가 아니라 **스텁 서버가 상대 종료(EOF)를 감지한 시각**으로 소켓 종료를 판정한다.

스텁: (1) 헤더(`Content-Length: 1000`)만 보내고 정지 (2) 헤더 + 본문 100바이트 후 정지 (3) accept·요청 수신 후 무응답.

| 스텁 | 방식 | 클라 실패(ms) | 예외 | 소켓 종료 감지(ms) |
|---|---|---|---|---|
| 헤더만 | `request.timeout(2s)`만 | **끝까지 실패 안 함** | - | **닫히지 않음** |
| 헤더만 | `cancel(true)` 2s | 2006~2011 | CancellationException | 2006~2012 (FIN) |
| 본문 절단 | `request.timeout(2s)`만 | **끝까지 실패 안 함** | - | **닫히지 않음** |
| 본문 절단 | `cancel(true)` 2s | 2006~2008 | CancellationException | 2006~2008 (FIN) |
| 무응답 | `request.timeout(2s)`만 | 2004~2006 | HttpTimeoutException | 2004~2005 (FIN) |
| 무응답 | `cancel(true)` 2s | 2003~2005 | CancellationException | 2003~2005 (FIN) |
| 3종 | `request.timeout` + `cancel(true)` 병용 | 2001~2005 | 헤더 수신 후엔 Cancellation, 무응답은 Cancellation 또는 HttpTimeout(경합) | 2001~2006 (FIN) |

발견:
- **`HttpRequest.timeout`은 응답 헤더 수신까지만 덮는다.** 헤더가 도착한 뒤 본문이 멈추면 무기한 대기하고 소켓도 닫히지 않는다(관측 창 6초 초과). PLAN M1.5의 1순위 측정 대상에 대한 답이다.
- **`sendAsync` 반환 future의 `cancel(true)`는 3종 모두에서 소켓을 실제로 닫는다**(서버가 FIN 감지, 지연 약 1~12ms). 진행 중 요청을 끊는 수단으로 충분하다.
- 병용 시 무응답 스텁은 두 타이머가 경합해 예외 종류가 달라진다. 호출부는 `CancellationException`과 `HttpTimeoutException`을 모두 "기한 초과"로 분류해야 한다.

채택: **A2 — `sendAsync` + 호출별 남은 기한에 `cancel(true)` 예약.** `request.timeout(남은 시간)`은 보조로만 둔다(권한은 cancel). Apache 기반(B)은 시도하지 않았다 — A2가 성공 기준을 충족해 의존성을 추가할 이유가 없다. M4(LLM)·M5(Slack) 전송 계층에 동일하게 적용한다.
한계: 루프백 정상 케이스만 측정했다. 실제 Ollama의 잔여 추론 종료, 연결 수립 정체(비라우팅 주소), 대용량 본문은 재지 않았다(M4 이후 관측).

## 4. 경계 실험 (M8)

미수행.
