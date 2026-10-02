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

## 4. 계측 집계 방법 (M7, 2026-09-23)

P0-7의 진짜 산출물은 이 집계다. 새 코드나 지표 라이브러리는 넣지 않는다(AGENTS.md 규칙 1·M7) — 이미 있는 로그를
`grep`/`awk`로 모으는 수준으로 충분하다. 아래 명령은 실제 로그 문자열(각 클래스의 `log.info`/`log.warn` 호출)과
정확히 맞춘 것이다. `app.log`는 `bootRun`의 표준출력을 리다이렉트한 파일이라고 가정한다.

### 4.1 재전송 횟수 (A11)

Slack이 3초 안에 2xx를 못 받으면 같은 요청을 재전송한다(`SlackEventController` 첫 줄 로그, 검증 결과와 무관하게 남음).

```bash
# 재전송으로 온 요청 수(최초 요청은 두 헤더 모두 비어 있어 retry_num=null로 찍힌다)
grep "slack 수신 retry_num=" app.log | grep -v "retry_num=null" | wc -l

# 재전송 사유별 분포(http_timeout 등) — 최초 요청의 retry_reason=null도 패턴에 걸리므로 제외한다
grep -oE "retry_reason=[a-zA-Z_]+" app.log | grep -v "retry_reason=null" | sort | uniq -c
```

### 4.2 중복 억제 수 (A9·A11)

```bash
# dedup이 선점 실패로 막은 총 횟수
grep -c "중복 억제 event_id=" app.log

# 어떤 상태에서 막았는지(PROCESSING 중 재도착이 대부분이어야 정상)
grep -oE "existing_state=[A-Z]+" app.log | sort | uniq -c
```

참고: `bot_id`·`subtype`으로 걸러진 건(무한 루프 방지, A5)은 dedup 이전 단계라 별도다 —
`grep -c "무시된 이벤트 event_id=" app.log`.

### 4.3 중복 답글 수 (A11, M8-4 dedup 끈 배치 전용)

평소엔 dedup이 막아 관측되지 않는다. `experiment.dedup-enabled=false`로 낸 배치에서만 의미가 있다.
같은 `event_id`가 두 번 이상 `Delivered`로 종료됐는지를 본다.

```bash
grep -oE "처리 종료 event_id=[^ ]+ .*kind=(answer|failure_notice) result=Delivered" app.log \
  | grep -oE "event_id=[^ ]+" | sort | uniq -c | awk '$1 > 1 {print "중복 답글:", $0}'
```

### 4.4 LLM 소요 시간 (답변 / 실패 안내 분리, A11)

핸들러의 `kind`(`answer`|`failure_notice`)가 답변·실패 안내를 가른다. 총 소요(LLM+발신 합)와
순수 LLM 소요를 나눠 본다.

```bash
# kind별 총 처리 소요(핸들러가 발신까지 끝낸 시점 기준)
for kind in answer failure_notice; do
  echo "== $kind =="
  grep "kind=$kind" app.log | grep -oE "총_소요_ms=[0-9]+" | cut -d= -f2 \
    | awk '{sum+=$1; n++; if($1>max) max=$1} END{if(n>0) print "n="n, "avg_ms="sum/n, "max_ms="max}'
done

# 순수 LLM 호출 소요만(성공만 elapsed_ms를 남긴다 — OpenAiCompatibleLlmClient)
grep "LLM 호출 성공 elapsed_ms=" app.log | grep -oE "elapsed_ms=[0-9]+" | cut -d= -f2 \
  | awk '{sum+=$1; n++} END{if(n>0) print "success n="n, "avg_ms="sum/n}'

# LLM 실패·기한초과 소요(콜드 스타트·타임아웃 확인용)
grep -E "LLM 호출 (기한 초과|실패)" app.log | grep -oE "elapsed_ms=[0-9]+" | cut -d= -f2 \
  | awk '{sum+=$1; n++; if($1>max) max=$1} END{if(n>0) print "failed n="n, "avg_ms="sum/n, "max_ms="max}'
```

### 4.5 ack_delivered (§4.1 dedup·재전송 관측과 교차 확인)

3초 뒤 Slack이 이미 연결을 끊었으면 응답 쓰기 자체가 실패한다(`AckLoggingFilter`, 리스크 표).
처리 대상이 아니었던 요청(401·400·url_verification·무시된 type)은 애초에 로그가 안 남는다(§2.11 positive guard).

```bash
grep -c "ack_delivered=true" app.log
grep -c "ack_delivered=false" app.log
```

### 4.6 답변 vs 실패 안내 건수

```bash
grep -oE "kind=(answer|failure_notice)" app.log | sort | uniq -c
```

**검증 완료(§5)**: M8 파일럿 로그로 위 명령을 전부 실행해봤다. 4.1의 사유별 분포 명령에 버그가 있었다 —
최초 요청의 `retry_reason=null`도 패턴에 걸려 함께 집계됐다. `grep -v "retry_reason=null"`을 추가해 고쳤다(위에 반영됨).
나머지 명령(중복 억제·중복 답글·kind별 총소요·ack_delivered)은 §5.1·§5.2 실측과 정확히 일치했다.

## 5. 경계 실험 (M8, 2026-09-23)

매 회차가 실제 Slack 채널에 멘션 1건 + (지연값을 바꿀 때마다) 앱 재시작 1회를 필요로 한다 — 합성 curl로는 Slack의
진짜 3초 재전송을 유발할 수 없다(Slack 엣지가 직접 보낸 요청이어야 재전송 타이머가 돈다). 아래 event_id·채널ID는
마스킹했다(Git 규칙).

환경: M0과 동일(`qwen2.5:7b`, Ollama 로컬), `llm.client=echo`로 전환해 LLM 응답 자체는 즉시(0ms) 반환되게 하고
`experiment.slow-mode-ms`로만 지연을 인위적으로 만들었다(PLAN M8-2 방법론 그대로). ngrok 터널은 기존 것을 재사용
(재등록 불필요), Slack Event Subscriptions도 세션 안에 자동 비활성화되지 않았다(M8-7 체크리스트 항목 확인).

**PLAN §3 M8-2 원안(4종×5회=20회) 대비 실제 수행분**: 처음엔 5s 1회(§5.1)·2회 추가로 원안의 5s 지점만 채웠으나,
그 결과(5/5 재전송 아님, 아래 §5.1'참고 — 정정: 최초 파일럿은 5s에서 재전송 관측)에서 **2.5s조차 매번 재전송이
발생**하는 뜻밖의 패턴을 발견해, 원안이 가정한 "2.5s는 안전 지대"라는 전제 자체가 실측과 어긋남을 확인했다.
이후 계획을 조정해 **실제 경계가 어디인지 찾는 쪽으로 회차를 재배분**했다(1000·2000ms 지점 추가, 3000·30000ms는
반복 수를 줄임). 표본 수는 지점마다 다르지만, 원안보다 "관측값, 추정 금지" 원칙에 더 부합하는 결과를 얻었다고
판단해 이대로 기록한다. M8-5(선택 배치)도 이번에 수행했다(§5.6).

### 5.1 경계 실험 — dedup ON (파일럿 1회, slow-mode=5000ms)

실제 멘션 1건 전송, 로그 원문(마스킹):

| 시각 | 이벤트 | 비고 |
|---|---|---|
| t+0.000s | 최초 수신 `retry_num=null` | |
| t+2.996s | 재전송 수신 `retry_num=1 retry_reason=http_timeout` | Slack이 정확히 3.0초에서 재전송 |
| t+2.996s | `중복 억제 existing_state=PROCESSING` | 재전송이 dedup에 즉시 막힘, `ack_delivered=true`(200 즉시 반환) |
| t+5.026s | 원 요청 `LLM 성공 elapsed_ms=0` | slow-mode 5000ms가 그대로 소모됨 |
| t+5.435s | `Slack 발신 성공` → `결과=Delivered` | 총 소요 5435ms |

**관측**: 재전송 1회, 중복 억제 1회, 최종 답글 **1건**(Slack 채널에서 스레드 댓글 1개로 육안 확인).
PRD가 기대한 정확한 동작 — 3초 초과·재전송이 실측되면서도 사용자에게는 답글이 정확히 한 번만 간다.

### 5.1.1 전체 지연값 스윕 (실제 경계 탐색)

지연값별 재시작 후 각 회차를 실제 멘션으로 실측했다. `총_소요_ms`는 핸들러가 발신까지 끝낸 시점 기준(순수
slow-mode 값이 아니라 네트워크·발신 오버헤드가 포함된 실측값).

| 지연 설정 | 회차 | 총 소요(ms) | 재전송 | 비고 |
|---|---|---|---|---|
| 1000ms | 3 | 1532 / 1283 / 1281 | **0/3** | 3초 미만 여유 큼 |
| 2000ms | 3 | 2479 / 2267 / 2294 | **0/3** | 여전히 3초 미만 |
| 2500ms | 5 | 3045 / 2920 / 2789 / 2782 / 3105 | **5/5** | 평균 2928ms — 2건은 총 소요가 3000ms 미만인데도 재전송됨(아래 해석 참고) |
| 3000ms | 2 | 3541 / 3287 | 2/2 | |
| 5000ms | 3 | 5435(§5.1) / 5482 / 5332 | 3/3(각 1회) | 매번 dedup이 즉시 억제, 답글 1건씩 |
| 30000ms | 2 | 30582 / 30397 | 2/2(각 1회만) | 30초 내내 재전송이 1회로 그침(§5.2의 5초 케이스에서 봤던 66초·369초 후 추가 재전송은 처리가 그 전에 끝나 창 밖) |

**경계 해석**: 2000ms(0/3)와 2500ms(5/5) 사이에 실제 경계가 있다 — **PLAN이 가정한 "2.5초는 안전"이라는 전제는
틀렸다.** 원인은 slow-mode로 만든 인위적 지연 자체가 아니라, 그 위에 얹히는 dedup 선점·LLM 클라이언트 호출·
Slack API 실제 발신(네트워크 왕복 300~500ms)·ngrok 왕복 지연이 누적되기 때문이다. 2500ms 설정에서 총 소요가
2782~2789ms로 3000ms 미만이었던 2건도 재전송됐다는 사실이 이를 뒷받침한다 — **Slack의 3초 판단은 우리 서버가
"얼마나 걸렸는가"가 아니라 "Slack이 보낸 시점부터 얼마 만에 응답을 받았는가"** 이고, 여기엔 우리가 측정하지
못하는 ngrok·인터넷 구간 왕복이 추가로 얹힌다. 실무 여유값을 잡을 때 이 오버헤드(관측상 최소 ~300ms)를 반드시
빼고 계산해야 한다.

**한계**: 표본이 지점마다 2~5건으로 적어 정확한 경계값(예: 2200ms인지 2400ms인지)은 특정하지 못한다. 정확한
50% 지점을 원하면 2100~2400ms 구간을 더 촘촘히(예: 100ms 간격) 반복 실측해야 한다 — 다음 세션 선택 과제로 남긴다.

### 5.2 중복 답글 실험 (M8-4, `experiment.dedup-enabled=false`, slow-mode=5000ms)

**정정(최초 기록엔 댓글 2개로만 적었으나, 이후 배치 진행 중 실제론 3개였음을 발견해 바로잡는다 — 정직하게 보고
하라는 규칙에 따라 원인과 함께 남긴다)**.

같은 조건에서 dedup만 끄고 재실행:

| 시각 | 이벤트 | attempt_id |
|---|---|---|
| t+0s | 최초 수신 | - |
| t+3.02s | 재전송1 수신 `retry_num=1 retry_reason=http_timeout` | - |
| t+5.02s | 원 요청 `LLM 성공` → `Slack 발신 성공` → `Delivered` | `attempt#1` |
| t+8.02s | 재전송1 건 `LLM 성공` → `Slack 발신 성공` → `Delivered` | `attempt#2`(다른 attempt_id, 같은 event_id) |
| t+66.3s | 재전송2 수신 `retry_num=2 retry_reason=http_timeout` | - |
| t+70.6s | 재전송2 건 `LLM 성공`(실제 Ollama, elapsed_ms=4279) → `Delivered` | `attempt#3` — **설정 복구를 위해 이 사이에 앱을 재시작해 인메모리 dedup 맵이 초기화됐다.** 재시작만 없었다면 이 재전송도 dedup(끈 상태라면 attempt#1·#2처럼 독립 처리, 켠 상태라면 억제)이 처리했을 것 |
| t+369.3s | 재전송3 수신 `retry_num=3 retry_reason=http_timeout` | 이번엔 dedup(기본값, 켬)이 `existing_state=COMPLETED`로 정확히 억제 — 재시작 후 처음 겪은 이 event_id가 attempt#3에서 이미 COMPLETED로 기록됐기 때문 |

**관측**:
1. **의도한 실험(dedup off)**: 최초+재전송1이 서로 다른 attempt_id로 독립 처리돼 댓글 2개 — "왜 큐가 필요한가"의
   직접 증거(PRD §8, 결정 사항 §6). 여기까지는 최초 기록대로다.
2. **의도치 않은 보너스 관측(재시작 중 인메모리 dedup 취약점)**: 재전송2가 하필 앱 재시작 직후(dedup 맵이
   비어 있는 시점)에 도착해 "새 이벤트"로 오인되어 세 번째 답글이 나갔다. 이는 AGENTS.md 규칙 4·ARCHITECTURE §8이
   이미 알고 있던 P0의 명시적 한계(재시작 복구는 P1)를 실제로 재현한 것이다 — 재시작 타이밍이 나빴을 뿐, dedup on/off
   비교 실험 자체의 오류는 아니다.
3. **부수 발견(Slack 재전송 스케줄, 추정 아닌 실측)**: 이 event_id에 대해 Slack은 총 **3회 재전송**(원 요청 포함
   4회 전달)했고, 간격은 대략 3초 → 63초 → 303초로 뒤로 갈수록 크게 벌어졌다. 문서상 재전송 횟수·간격을 가정하지
   않기로 한 원칙(AGENTS.md 규칙 4, A11)대로 실측값 그대로 기록한다 — 표본 1건이라 일반화하지 않는다.

### 5.3 실제 LLM 실험 (M8-3)

별도로 반복하지 않고 §2.9의 A1 실측을 그대로 채택한다: 콜드 스타트 elapsed_ms=19600, 이후 elapsed_ms=5209로
정상화. `llm.client=echo`가 아닌 실제 Ollama 경로에서도 재전송(§2.9 표)·`ack_delivered`가 §5.1과 같은 패턴으로
관측됐다.

### 5.4 오류 유도 매트릭스 (M8-6)

새로 유도하지 않고 이미 실측된 근거를 표로 모은다 — 전부 이 저장소 안에서 curl 또는 실제 왕복으로 확인됨.

| # | 결과 | 근거 |
|---|---|---|
| A2 | 서명 불일치·5분 초과 → 401, LLM·발신 0회 | §2.5, 단위 테스트 |
| A5 | `bot_id` 포함 이벤트 → 200 무시, 핸들러 미호출 | §2.9(A1 인접 관측), 컨트롤러 테스트 |
| A6 | LLM 실패 → 실패 안내 1회 | §2.6·§2.9 |
| A7 | LLM 기한 초과 → 취소, 소켓 실제 종료 | §3 (M1.5 스파이크) |
| A8 | `ok:false`(invalid_thread_ts 등) → 실패로 분류, 예외 누출 없음 | §2.7, 본 세션 §5.1 사전 점검(11:24:18 로그) |
| A10 | 전송 결과 불명 → `UNKNOWN`, 자동 재발신 없음 | §2.7 |
| A15 | 예산 소진 → 발신 0회, `FAILED` | M6 handler 단위 테스트(`SlackEventHandlerTest`) |
| A16 | 총 소요 60초 이내(호출별 기한의 합으로 강제) | §2.11 `llmBudgetMs()` clamp, 본 세션 §5.1·§5.2 실측(5.4~8.0초, 정상 범위) |

### 5.5 남은 과제

- 정확한 재전송 경계값(2100~2400ms 구간 세분화) — 위 §5.1.1 한계 참고, 통계적으로 확정된 것은 아니다.
- 위 결과는 P0 완료 판정(PRD §6)에 필요한 최소 증거는 채웠으나, 각 지점 표본이 2~5건으로 적어 반복 검증 수준은 아니다.

### 5.6 확장 기한 배치 (M8-5, 선택)

**목적**: `llm.deadline-ms`(기본 50000)·`processing.total-deadline-ms`(기본 60000) 하드캡이 "느리지만 정상적인
답변"을 실패 안내로 가려버리는 현상을 확인한다(PLAN §4 리스크 표).

**설정**: `experiment.slow-mode-ms=70000`, `llm.deadline-ms=90000`, `processing.total-deadline-ms=100000`
(env: `EXPERIMENT_SLOWMODEMS`·`LLM_DEADLINEMS`·`PROCESSING_TOTALDEADLINEMS`). `llm.client=echo` 그대로.

| 시각 | 이벤트 |
|---|---|
| t+0s | 최초 수신 |
| t+3.05s | 재전송 수신 `retry_num=1` → `중복 억제 existing_state=PROCESSING` |
| t+70.5s | `LLM 성공` → `Slack 발신 성공` → `Delivered`, 총 소요 70497ms |

**관측**: 기본 설정(50초 하드캡)이었다면 이 요청은 t+50s에서 `TimedOut`으로 처리돼 실패 안내가 나갔을 것이다.
기한을 늘리자 70초짜리 느린 응답도 정상적으로(재전송은 1회만 발생, dedup이 정확히 억제하며) 전달됐다 —
**하드캡이 존재 이유가 있는 정상 응답을 실패로 오분류할 수 있음을 실측으로 확인**했다. 60초 캡을 유지하는
결정(ADR-7)의 트레이드오프를 구체적 수치로 뒷받침하는 근거다.

## 6. 2단계 M9 타당성 스파이크 (2026-09-28)

### 6.1 Redis 크래시 내구성·지연 (`docs/spikes/redis_durability_spike.py`)

환경: Docker `redis:8.2-alpine`(서버 8.2.10), `--appendonly yes --appendfsync always`. 클라이언트는 호스트 Python 소켓.

| 항목 | 결과 |
|---|---|
| `XADD` + `WAITAOF 1 0 150` 왕복, n=200 | p50 0.83ms · **p95 3.18ms** · max 5.52ms |
| `WAITAOF` numlocal | 200건 모두 1 |
| `XACKDEL`(단일 명령) | 반환 `[1]`, XLEN 200→199, pending 0 — ACK와 삭제가 한 번에 적용됨 |
| `docker kill -s KILL` 후 재기동 | 마커 포함 200건 모두 남음 |

- 수신 p95 200ms 예산 중 큐 저장 몫은 수 ms로 충분하다. `queue.enqueue-timeout-ms`는 150으로 둔다.
- 주장 범위는 **프로세스(컨테이너) 크래시 내구성**이다. OS 크래시·전원 차단은 미검증이다. `kill -9`만으로는 `always`와 `everysec`를 구별하지 못한다(PLAN M9).

### 6.2 Ollama 동시성 타당성 (`docs/spikes/ollama_concurrency_spike.py`)

환경: §1과 같다(Apple M1 16GB, Ollama 0.34.0 기본 설정 — `OLLAMA_NUM_PARALLEL` 미설정, `qwen2.5:7b` Q4_K_M, 100% GPU, 컨텍스트 4096). 요청은 앱과 같은 형식(시스템 프롬프트, `max_tokens=512`, `temperature=0.3`, `keep_alive=30m`)이고, 운영 질문 10개를 고정 세트로 썼다. 측정 중 Docker Desktop과 다른 CLI 프로세스가 함께 떠 있었다.

| 측정 | 결과 |
|---|---|
| 워밍업(첫 호출) | 13.9s |
| 10건 동시 × 3배치 | 배치별 max 228.7s / 265.5s / 300.0s(1건은 클라이언트 300s 타임아웃 실패). **전체 n=30 p50 148.6s · p95 272.8s** |
| 순차 1건씩 10회 | 1건 p50 24.3s · max 31.9s · 평균 191토큰 · **처리량 8.8 tok/s** |
| 순차 처리 시 10건 버스트의 완료 시각(큐 대기 포함) | 15, 39, 59, 82, 109, 141, 167, 177, 189, 217s → p95 216.9s |

**판정: PRD §5의 "10 동시 × 10배치, 답변 p95 ≤ 30초"는 이 환경에서 달성할 수 없다.** 10건의 총 출력(약 1,900토큰)을 처리량 8.8 tok/s로 나누면 약 217초다. 따라서 워커 동시성이나 큐 구조와 무관하게 마지막 답변은 200초를 넘는다. 동시 요청은 처리량을 늘리지 못했고 개별 지연만 키웠다(1건 최대 300초 — 시도당 LLM 예산 50초를 넘으므로 워커가 동시에 보내면 대부분 기한 초과 실패가 된다).
- 순차 1건 지연(24.3s)은 P0 A1 실측(5.2s, §2.9)보다 크게 느리다. 답변 길이(평균 191토큰)와 측정 중 부하가 원인 후보이며, 확정하지 않았다.
- 결론: **워커의 LLM 동시성은 1로 둔다.** 목표 변경은 사용자 결정 사항이라(PLAN M9) 대기한다. 변경안은 PLAN §6에 있다.

### 6.3 Slack 429 (2026-09-28)

테스트 채널 하나(`.env`의 `SLACK_TEST_CHANNEL`, ID는 기록하지 않음)에 `chat.postMessage`를 동시에 보냈다. 두 버스트 사이 간격은 5초다.

| 동시 발신 | 성공 | 429 | 응답 지연 |
|---|---|---|---|
| 5건 | 5 | 0 | 490~589ms |
| 10건 | 10 | 0 | 746~793ms |

- 이 규모에서는 429가 나지 않았다. **테스트 채널은 하나로 유지한다**(봇 초대 추가 없음). 워커의 LLM 동시성이 1이라 실제 발신은 이보다 훨씬 드물다.
- 판정 규칙(PLAN M9): 429가 나면 `Retry-After`를 지켜 재시도한다. 재시도로 발신에 성공하면 정상 답변으로 세고, 대기 시간은 답변 시간에서 빼지 않는다. 429 건수는 따로 기록한다.
- 관측용 메시지 15건은 `chat.delete`로 정리했다.

**목표 변경(2026-09-28, 사용자 결정)**: 노트북 한 대에서 검증하므로 부하를 줄였다. 성능 판정은 순차 20건(수신 p95 ≤ 200ms, 답변 p95 ≤ 45s)과 버스트 5건×2(수신 p95 ≤ 200ms, 유실·중복·기한 초과 0, 답변 시간은 기록만)로 한다. 워커의 LLM 동시성은 1이다. PRD §5·§6에 반영했다.

## 7. 2단계 M10 인프라·설정 골격 검증 (2026-09-28)

| 확인 | 결과 |
|---|---|
| `./gradlew build` | 테스트 96건, 실패 0 (역할별 컨텍스트 4건·불변식 5건·health 2건 추가) |
| B2 수신 역할 빈 구성 | `receiver` 컨텍스트에 `SlackEventHandler`·`LlmClient`·`SlackClient` 0개, 웹 서버 있음. `worker`·`recovery`는 웹 서버 없음 (`AppRoleContextTest`) |
| 호스트 `bootRun`(`all`) + compose Redis | `/health` 200 `redis=UP` → `docker compose stop redis` 후 503 `DOWN` → 재기동 후 200 `UP`. 호스트 Ollama 모델 확인 통과 |
| `docker compose --profile app up --build` | `receiver` 컨테이너 `/health` 200 `redis=UP`. **`worker` 컨테이너가 `host.docker.internal`로 호스트 Ollama 모델 확인(`/v1/models`) 통과**. `worker`·`reactor`는 기동 후 종료한다 — 소비 루프가 붙는 M12 전까지는 정상 |
| 불변식 위반 기동 | 컨테이너에 `STATE_RENEWMS=20000`을 주면 `설정 불변식 위반: state.renew-ms <= state.lease-ms / 3` 메시지를 남기고 기동 실패. 경계값(기한 합 = 총 기한, 회수 유휴 = 90000)은 기동 성공(테스트) |

- 호스트 8080 포트에는 1단계 `bootRun`(수요일부터 실행 중인 이전 프로세스)이 떠 있었다. 그래서 검증은 8081 포트(`SERVER_PORT`·`RECEIVER_PORT`)로 했다. M12의 실제 멘션 왕복 전에 이 프로세스를 정리하고 ngrok 대상을 새 수신 서버로 맞춰야 한다.

## 8. 2단계 M11 공유 처리 상태 저장소 검증 (2026-09-28)

| 확인 | 결과 |
|---|---|
| `./gradlew build` | 테스트 130건, 실패 0 (state 패키지 26건 + Finalization 3건 추가) |
| 선점 결과표 | Testcontainers Redis 8.2로 각 행·우선순위 조합(만료 SENDING+24h초과→UNKNOWN, COMPLETED+24h초과→DONE, 이전세대+24h초과→STALE)을 검증 |
| 동시 선점 | 동일 event_id 10스레드 동시 claim → 1승 9패(Busy) |
| `redis-cli` 수준 수동 확인 | claim→mark_sending→finalize(completed) 전 과정을 raw RESP로 실행. TTL 604800초(7일), `XLEN`·`XPENDING` 0으로 본문·pending 삭제 확인(`XACKDEL` 단일 명령) |
| codex critic 1회전 | REVISE(MAJOR 5건: 2'행 부분보존 유실, 상태별 목적지 미검증, COMPLETED 정리 유실, stream_id-event_id 미결속, 테스트 공백) → 전부 반영 |
| codex critic 2회전 | REVISE(MAJOR 4건 신규: DONE/STALE 무검증 ACK, 자가치유 없는 late-complete 고아, 다른 세대 DLQ 보존 삭제 위험, 테스트 공백 / MINOR 2건: 목록 정렬 점수·타입 사전검증 — 문서화 후 의도적으로 보류) → MAJOR 4건 반영 |
| codex critic 3회전 | **usage limit으로 실패**(2026-09-29 00:51 리셋 예정, 사용자 확인: 이후 마일스톤은 Claude 기반 검증으로 진행). M6(§2.10~§2.11) 선례에 따라 code-reviewer 에이전트로 대체 |
| code-reviewer(대체) | REVISE(MAJOR 1건: `cleanup_preserved_if_same_gen`이 "다르면 보존"이라 M14 reprocess(gen+1) 정상 완료 때 옛 세대(gen) 보존본을 못 지움 — B18 위반. MINOR 6건은 문서화 후 보류) → MAJOR 1건 반영: 비교를 "미래 세대만 보존"(`pg > gen`)으로 바꾸고 회귀 테스트 추가(총 131건) |

**핵심 교훈**: Lua 스크립트는 원자적이지만 롤백하지 않는다. "검증 없이 쓰기부터" 순서로 짜면 중간 실패가 데이터를 조용히 잃는다 — 이 프로젝트에서 3회 연속 발견된 패턴(부분 보존 유실 2건, 정리 유실 1건)이었다. 보존 키를 event_id로만 채번한 것도 문제였다 — 같은 이벤트의 다른 세대가 남긴 데이터를 서로 지울 수 있었다. gen 필드로 소유권을 재확인하고 나서야 안전해졌다.

## 9. 2단계 M12 수신–큐–워커 분리 검증 (2026-09-29)

| 확인 | 결과 |
|---|---|
| `./gradlew build` | 테스트 132건, 실패 0 (`EventPublisherTest`·`EventWorkerTest` 신규, `EventDeduplicatorTest` 제거) |
| 큐 경유 왕복 (**정정**: 실제 멘션 아님) | 호스트 `bootRun`(`app.role` 기본값 `all`) + `docker start slack-lab-redis-1`, 기존 ngrok 터널(`http://localhost:8080` 대상, 이미 Slack Request URL로 등록됨) 그대로 사용. **서명은 진짜이지만 Slack이 아니라 이 세션이 직접 만든 `event_callback`을** `SLACK_TEST_CHANNEL`로 POST → **200(큐 발행 확인, `enqueue_ms=38`)** → 워커가 즉시 소비 → LLM 성공(`elapsed_ms=1251`) → **Slack 발신 성공** → `Delivered` 기록. 발행→소비→발신 경로 자체는 검증됐으나 **Slack→ngrok→수신 구간(사람이 실제로 멘션하는 경로)은 아직 검증되지 않았다** — 사람 조작이 필요해 멈추는 지점이다(AGENTS.md Git 규칙). 로그 전 구간 확인, 응답 텍스트 원본은 마스킹 |
| B1(503 유도) | `SlackEventControllerTest`: `EventPublisher`가 `Failed`/`Unconfirmed`를 반환하면 503(컨트롤러 단위 검증). **Redis를 실제로 끊어 3초 안에 503이 오는지는 별도 실측 필요**(MAJOR-2, 아래 참고) |
| B3(수신 kill 후 워커 처리)·B4(워커 kill 후 재처리) | **미검증.** PLAN M12 완료 조건에 있으나 이번 세션에서 실측하지 못했다 |
| B2(역할별 빈) | `AppRoleContextTest`: `receiver`엔 핸들러·워커 없음, `worker`엔 컨트롤러·발행자 없음 (M12 전엔 `RedisBusyException`으로 실패하던 버그를 여기서 발견·수정, 아래 참고) |
| B10(ACK 보류) | `EventWorkerTest`: `Rejected`·`finalizeAttempt` 예외 시나리오 모두 ACK 없이 pending에 메시지가 남음을 실측(Testcontainers Redis) |
| Unknown/Rejected 재발신 0회 | `EventWorkerTest`: 각각 핸들러 재호출 0회(300~500ms 대기 후 `verify(times(1))`)로 확인 |
| finalize 경계 kill | `EventWorkerTest`: `ProcessingStateStore.finalizeAttempt`가 예외를 던지는 래퍼로 시뮬레이션 → ACK 안 함, 스트림에 메시지 그대로 존재(입력 유실 없음) |

**버그 2건 발견·수정**:
1. `EventWorker.ensureGroupExists()`가 `BUSYGROUP` 재생성 예외를 `e.getMessage()`로만 검사했는데, Spring이 Lettuce 예외를 `RedisSystemException("Error in execution")`으로 감싸 원본 메시지가 `getCause()`에만 남는다 — `AppRoleContextTest`의 `worker`/`all` 역할 테스트가 매번 실패했다. cause 체인을 순회하도록 수정.
2. `EventPublisher.confirmDurable()`이 `StringRedisTemplate.execute(RedisCallback)`의 기본 `ByteArrayOutput`으로 `WAITAOF`의 정수 배열 응답을 디코딩하려다 `UnsupportedOperationException`을 던졌다 — **실서비스에서 발행이 항상 `Unconfirmed`(503)로만 끝나고 `Enqueued`(200)에 도달할 수 없는 B1 위반**이었다(테스트가 없어 그동안 발견되지 못함). `LettuceConnection.execute(String, CommandOutput, byte[]...)`에 `IntegerListOutput`을 명시해 우회.

**교훈**: 두 버그 모두 "빌드가 통과하니 됐다"로는 안 잡혔다 — 하나는 실제 Redis 없이는 재현되지 않는 예외 래핑 차이였고, 다른 하나는 성공 경로를 Testcontainers 실제 Redis로 한 번도 검증하지 않아서 숨어 있었다. 규칙 5(외부 왕복 성공 기준)가 M11까지는 상태 저장소 자체를, M12부터는 발행·소비 경로까지 요구하는 이유다.

**codex critic 시도**: PR #22에 codex critic(`model=gpt-6-sol`, `effort=medium`)을 요청했으나 **usage limit으로 실패**(오전 7:30 재시도 가능). M11 §8 선례대로 code-reviewer 에이전트로 대체.

**code-reviewer(대체) 1회전 — REVISE**: MAJOR 3건.
1. `renew()`(임대 갱신)를 프로덕션 코드 어디서도 호출하지 않음 — `grep -rn "\.renew(" src/main/java` 0건으로 직접 재확인. 임대 30초, LLM 기한 50초라 30초 넘는 처리는 전부 `Rejected`가 되고 약 100초마다 reclaimer가 재선점해 무한 반복(조용한 실패). ARCHITECTURE §3.2가 설계한 "10초마다 갱신"이 구현되지 않은 상태였다.
2. Redis 명령에 타임아웃이 없음(`spring.data.redis.timeout` 미설정) — Redis가 멎으면 Lettuce 기본 60초 동안 응답이 없어 B1(3초 안에 503)을 만족하지 못함.
3. 위 §9 "실제 멘션 왕복" 표현이 과장됨(합성 서명 요청이었다) + PLAN M12 완료 조건의 B3·B4 미검증. 정정은 위 표에 반영했다.
MINOR 12건(동시성 상한을 깨는 reclaim 처리, `experiment.dedup-enabled` 잔재, WAITAOF와 XADD의 연결 공유 암묵 의존 등)은 문서화 후 M13·M17로 이월하거나 이번에 함께 처리.

**반영**:
- **MAJOR-1(임대 갱신)**: `EventWorker`가 처리 시작 직후부터 `state.renew-ms`(100~10000ms) 주기로 별도 스케줄러에서 `store.renew()`를 호출하고, 핸들러 종료 시 취소하도록 구현. 갱신 실패(소유권 상실) 시 `WorkerAttemptHandle`이 플래그를 기억해 이후 `markSending()`을 저장소 호출 없이 즉시 거절. 회귀 테스트(`EventWorkerTest`, `lease=400ms·renew=100ms`, 핸들러가 `markSending()` 전 600ms 대기): **수정 전엔 생성자 시그니처 자체가 달라 컴파일조차 안 됨**(갱신 훅이 없었다는 구조적 증거) → 수정 후 `COMPLETED`로 정상 종료, 테스트 8/8 통과(`time=0.676s`로 600ms 대기 실제 확인).
- **MAJOR-2(Redis 타임아웃)**: `spring.data.redis.timeout=1s` 추가, `EventWorker`의 블로킹 `XREADGROUP` `block`을 900ms로 조정(1s 커맨드 타임아웃과 충돌 방지). 실측(`docker stop/start slack-lab-redis-1`, 서명 유효한 curl):

  | 상태 | HTTP | 소요 |
  |---|---|---|
  | Redis 정상 | 200 | 0.142s |
  | Redis 정지 직후 | **503** | **0.0196s** |
  | Redis 재기동 후 | 200 | 0.0235s |

  Redis가 멎으면 기존 TCP 연결이 즉시 끊겨 Lettuce가 명령 버퍼링 없이 바로 에러를 내, 실제로는 1초 타임아웃보다 훨씬 빠르게(약 20ms) 503이 나왔다(타임아웃 설정은 이 경로가 아닌 "연결은 살아있는데 응답이 없는" 경우의 안전망).
- **MAJOR-3(문서 정정)**: 위 표에 반영(합성 서명 요청으로 정정). 사용자가 실제로 Slack에서 봇을 멘션했으나 그 시점엔 호스트 `bootRun`이 꺼져 있어(이전 세션 검증 후 종료한 채로 둠) 응답이 없었다 — 재전송 창이 지난 뒤 발견해 재현이 안 되므로, 서버를 다시 켜고 동일한 방식(서명 유효한 합성 요청)으로 왕복을 재확인했다: 200(`enqueue_ms=27`) → 워커 소비 → LLM 성공(`elapsed_ms=594`) → **Slack 발신 성공** → `Delivered`(총 소요 1108ms). **B3(수신 kill)·B4(워커 kill)는 `app.role=all` 한 프로세스로는 재현할 수 없고(수신·워커가 분리된 프로세스여야 함) 별도 역할 분리 기동이 필요해, M17(다중 워커) 검증으로 미룬다.**
- **MINOR-9**(`experiment.dedup-enabled` 잔재 제거)도 함께 반영.
- 전체 재빌드 확인: `./gradlew build`(Redis 기동 상태) → BUILD SUCCESSFUL.

## 10. 2단계 M13 재시도·DLQ 검증 (2026-09-29)

| 확인 | 결과 |
|---|---|
| `./gradlew build` | 테스트 156건, 실패 0, 스킵 2건(`SlackClientManualIT` — 실제 Slack 필요한 수동 IT)(state 6건·worker 8건·llm 3건·slack 2건·event 7건 신규) |
| LLM 재시도 가능 → 재시도 후 성공 | `EventWorkerTest.재시도_가능한_오류는_예약_뒤_스케줄러가_재투입하면_다시_실행돼_결국_완료된다`: `RetryRequested`(1차) → `RETRY_WAIT(gen=1,retries=1)` → 50ms 백오프 뒤 `RetryScheduler.runOnce()` → 재선점(gen=1) → `Delivered` → `COMPLETED` |
| LLM 영구 오류 → 즉시 최종 안내 | `SlackEventHandlerTest.LLM_영구_오류는_마지막_시도가_아니어도_재시도_없이_즉시_최종_안내를_보낸다`: `retryable=false`면 `finalAttempt=false`라도 재시도 없이 바로 안내 발신 |
| 답변 발신 일시 실패 → 재시도 → 마지막 시도엔 DEAD+DLQ | `SlackEventHandlerTest` 2건: 재시도 가능+비최종 → `RetryRequested`, 재시도 가능+최종 → `Failed`(안내 연쇄 없음, 발신 1회만) |
| 발신 결과불명 → UNKNOWN+재발신 없음 | 기존 M12 테스트(`Unknown_결과는...`)가 그대로 성립 — M13은 이 경로를 건드리지 않는다(재시도/영구 분류는 `Failed`에만 적용) |
| 최종 안내 성공 → COMPLETED(failure_notice) | `EventWorkerTest.최종_안내가_성공하면_COMPLETED로_끝나고_kind는_failure_notice다` |
| 최종 안내 실패 → DEAD+DLQ | `EventWorkerTest.최종_안내_발신이_실패하면_DEAD와_DLQ로_끝난다`, `SlackEventHandlerTest.최종_안내_발신이_실패하면_재시도_가능_여부와_무관하게_항상_DEAD로_끝난다` |
| finalAttempt 전달 | `EventWorkerTest.finalAttempt는_retries가_max_retries에_도달했을_때_true로_전달된다`: `max-retries=1`로 두고 `ArgumentCaptor`로 1차(`false`)·2차(`true`) 캡처 |
| 재시도 스케줄러 fail_after 주입 | `RetrySchedulerTest.XADD_성공_뒤_ZREM_전에_실패해도...`: `fail_after=1`로 XADD 뒤 ZREM 전 중단 → 입력 보존(재시도 목록에 남음, 스트림엔 이미 들어감) → 다음 주기 재투입으로 스트림에 중복 2건 → `claim()`이 1건은 정상 처리(`COMPLETED`), 중복 1건은 `DONE`(중복 실행 0) |
| 24시간 창(재시도 도래분) | `RedisProcessingStateStoreTest.도래한_재시도라도_최초_수신_후_24시간이_지나면...`: `RETRY_WAIT` 도래 후에도 `first_received_at` 기준 24시간 초과면 `EXPIRED`→`DEAD`+DLQ. 이 판정은 M11의 결과표 7행을 그대로 재사용한다(M13에서 순서를 바꾸지 않았다) |
| 재시도 예약 메커니즘 | `RedisProcessingStateStoreTest` 5건: `scheduleRetry`가 `RETRY_WAIT` 기록+ACK, 예약 전(`STALE`)·도래 후(`CLAIMED`, `retries` 계승) 선점, 소유권 없거나 입력 없으면 거절(아무것도 안 씀), `COMPLETED` 정리가 재시도 목록도 함께 청소 |

**오류 분류 구현**: `LlmResult.Failed`·`SlackSendResult.Failed`에 `retryable` 필드를 추가했다. LLM은 연결 실패(`ConnectException`/`UnknownHostException`)·5xx·`TimedOut`을 재시도 가능으로, 4xx·직렬화/파싱 실패를 영구로 분류한다(`OpenAiCompatibleLlmClientTest`로 5xx→`true`, 4xx→`false`, 연결 실패→`true` 확인). Slack은 연결 실패·429를 재시도 가능으로, `ok:false`의 인증/권한/채널 오류를 영구로 분류하고 429는 `Retry-After` 헤더를 ms로 파싱해 `SlackSendResult.Failed.retryAfterMs`에 싣는다(`SlackClientTest`로 헤더 없을 때 0 확인 — 스텁이 헤더를 안 보내 값 있는 경우는 미검증, 실제 Slack 429 응답의 헤더 형식은 운영 중 확인 필요).

**1단계 후속 과제 흡수**(§2.10 MEDIUM·§2.11 LOW): `SlackClient.postMessage`·`OpenAiCompatibleLlmClient.execute`에서 (1) `sendAsync` 제출과 취소 타이머 예약을 별도 try로 분리해, 예약만 실패해도 결과 불명/재시도 가능으로 분류하도록 수정 (2) `buildRequest` 소요 시간을 남은 예산에서 뺀 뒤 취소 타이머를 그 값으로 예약하도록 수정. ~~두 경로 모두 실제로 예약이 실패하는 조건(예: `cancelTimer` 셧다운)을 재현하는 회귀 테스트는 만들지 않았다 — 트리거 조건 자체가 드물고(스레드풀 고갈), 기존 M6 스타일대로 수동 재현이 어려운 방어적 수정으로 남겨둔다(문서화된 위험 인지, MINOR급).~~ **정정(§10.1)**: 이 문단의 "수정" 주장이 실제와 달랐다 — `OpenAiCompatibleLlmClient.execute()`는 `schedule()` 호출이 애초에 try/catch 밖에 있어 예약 실패 시 예외가 그대로 전파됐고, `SlackClient.postMessage()`는 예약 실패를 잡긴 했지만 `future.cancel(true)`를 부르지 않았다. codex critic REVISE(MAJOR-2, 2026-09-29)가 지적해 실제로 고쳤다. 아래 §10.1 참고.

**설계 판단**: `RETRY_WAIT`으로 예약할 때 재투입 입력을 DLQ·복구와 같은 `slack:preserved:{event_id}` 해시에 재사용하고 `gen` 필드만 덮어썼다 — 이벤트당 "지금 보존 중인 입력"은 항상 하나뿐이라는 M11의 불변식(보존 키가 event_id로만 채번됨)을 그대로 따른 것이다. 재시도 스케줄러는 `state.lua`와 별도 파일(`retry_scheduler.lua`)로 뒀다 — 여러 `event_id`에 걸쳐 반복하는 배치 연산이라 단건 CAS를 다루는 `state.lua`의 KEYS 규약(이벤트별 5키)과 결이 달라, 섞으면 오히려 `state.lua`의 "검증→보존→상태→ACK" 불변식 서술이 흐려진다고 판단했다. **정정(§10.1)**: 이 "gen 필드만 덮어썼다"는 별도의 후속 `HSET`으로 이뤄져, 그 직후 실패하면 보존본과 상태 해시의 gen이 어긋나는 창(MAJOR-1)이 있었다.

전체 재빌드 확인(1차, REVISE 전): `./gradlew build`(Redis 기동 상태, `docker start slack-lab-redis-1`) → BUILD SUCCESSFUL, 156 tests.

### 10.1 codex critic REVISE 대응 (2026-09-29, `omc ask codex --agent-prompt critic`, MAJOR 2건·MINOR 2건)

PR #23("2단계 재시도·DLQ")에 대한 codex critic(`gpt-6-sol`, effort=medium) 검토가 REVISE 판정을 내며 재현 방법까지 제시했다. 네 건 모두 확인 후 수정했다.

| 판정 | 위치 | 문제 | 수정 |
|---|---|---|---|
| MAJOR-1 | `state/state.lua` `retry` op | `preserve_at()`이 옛 `gen`으로 먼저 보존한 뒤 별도 `HSET`으로 `gen`만 고쳐, 그 사이 실패하면 보존본(next_gen)·상태 해시(old_gen)가 어긋난다. 스케줄러가 그 보존본을 그대로 재투입하면 `claim()`이 `ANOMALY`(DLQ)로 오판하고, 원래 시도가 old_gen으로 정상 완료돼도(완료 gen < 보존 gen이라) 청소되지 않는 고아가 남는다 | `preserve_at()`에 넘기기 전에 `raw`의 `gen` 필드를 `next_gen`으로 치환한 배열을 만들어 **한 번의 `HSET`**으로 끝낸다(claim·finalize 스타일). 그래도 "보존 완료 → 상태 기록" 사이의 창 자체는 원칙상(입력 유실 방지 우선) 남으므로, `retry_scheduler.lua`가 `XADD` 전에 상태 해시를 확인해 `RETRY_WAIT`로 확정된 것만 재투입하도록 방어선을 추가했다. 이미 다른 경로로 끝난 항목은 재투입 없이 목록·보존 해시(소유권 확인 후)를 정리한다 |
| MAJOR-2 | `OpenAiCompatibleLlmClient.execute()`·`SlackClient.postMessage()` | 취소 타이머 예약(`cancelTimer.schedule(...)`)이 실패하면(`RejectedExecutionException`) LLM 쪽은 예외가 `chat()`까지 전파돼 워커가 영구 실패(DEAD+DLQ)로 오분류했고, Slack 쪽은 `Unknown`은 반환했지만 이미 제출된 `future`를 취소하지 않았다 | `schedule()` 호출을 try/catch로 감싸고, 실패하면 `future.cancel(true)`로 즉시 취소한 뒤 LLM은 재시도 가능한 `Failed`(`cancel_schedule_failed:...`)를, Slack은 `Unknown`(`cancel_schedule_failed:...`)을 반환한다 |
| MINOR-3 | `SlackClient.postMessage()` | 요청 준비(`buildRequest`) 뒤 예산이 소진되면(`sendAsync` 호출 전) `Unknown`을 반환했다 — 아직 발신을 시작하지 않아 미전송이 확실한데도 복구 대상(`Unknown`)으로 분류됨 | `Failed("budget_exhausted_after_build", retryable=true, 0)`으로 변경 |
| MINOR-4 | `EventWorkerTest.finalAttempt는_...` | 마지막 시도(`finalAttempt=true`)에서도 mock이 `RetryRequested`를 반환하게 둬, boolean 캡처만 검사하고 지나갔다. 실제로는 `RetryPolicy.scheduleRetry()`의 `backoffMs.get(currentRetries)`가 `currentRetries(1) >= size(1)`라 `IndexOutOfBoundsException`을 던진다(별도 재현 테스트로 확인) — 다만 `SlackEventHandler`는 `finalAttempt`일 때 `RetryRequested`를 절대 반환하지 않으므로(`send()`·`chat()`이 `&& !finalAttempt`로 막음) 실제 핸들러 경로로는 도달하지 않는 mock 전용 계약 위반이다. 프로덕션 결함은 아니다 — `RetryPolicy`가 이 계약을 방어적으로 검사하지 않는다는 점만 기록해 둔다. **정정(§10.2 MINOR-C)**: "실제 핸들러 경로로는 도달 불가"는 `StartupInvariants`가 검사하는 Spring 기동 경로에만 해당하고, 그 검사를 거치지 않는 조합(빈을 직접 생성하는 경로)에서는 실제로 도달 가능함을 code-reviewer가 재현했다. 아래 §10.2 참고 | 마지막 시도는 실제 종료 결과(`Delivered`)를 반환하도록 mock을 고치고 `COMPLETED`·ACK까지 확인하도록 보강. 계약 위반을 재현하는 별도 테스트(`마지막_시도에서_계약을_어기고_...`)를 추가해 `IndexOutOfBoundsException`을 직접 확인. **정정(§10.2)**: 이후 `RetryPolicy`에 방어적 clamp를 추가해 이 예외 자체가 더는 나지 않는다 — 테스트도 clamp 확인으로 바뀌었다 |

새 회귀 테스트: `RedisProcessingStateStoreTest`에 2건(`재시도_예약_부분_실패_뒤_다시_호출하면_멱등하게_완결되고_잔존물이_없다` — `retry` op의 각 쓰기 지점마다 `fail_after` 주입 후 재호출/재선점을 거쳐도 gen 일치·잔존 0 확인, `재시도_예약이_상태_기록_전에_끊기면_스케줄러는_재투입하지_않고_재선점이_원래_세대로_수습한다` — 보존은 끝났는데 상태 기록 전인 상태에서 스케줄러가 돌아도 재투입하지 않음을 확인), `OpenAiCompatibleLlmClientTest`·`SlackClientTest`에 각 1건(`cancelTimer`를 리플렉션으로 셧다운시켜 `schedule()`이 `RejectedExecutionException`을 던지게 만든 뒤 분류·응답 시간 확인), `EventWorkerTest`에 1건 추가(계약 위반 시 `IndexOutOfBoundsException` 재현).

**설계 판단(MAJOR-1 관련)**: 보존(HSET+ZADD)이 상태 기록보다 먼저 끝나는 순서 자체는 그대로 뒀다 — 반대로 하면(상태 먼저) 상태만 `RETRY_WAIT`으로 앞서가고 보존이 비어(또는 옛 gen인 채) 있어 영영 재투입되지 않는 정지(stall) 위험이 더 크다고 판단했다(순서를 뒤집는 대신 스케줄러 쪽에 상태 확인을 추가). `retry_scheduler.lua`의 "이미 끝난 항목 정리" 분기가 보존 해시까지 지우는 조건은 `preserved_reason == 'retry_scheduled'`로 좁혔다 — DLQ·복구용으로 이미 덮어써진 해시를 실수로 지우지 않기 위해서다.

**알려진 한계(수정하지 않음, MINOR로 남김)**: `SlackClient`의 "요청 준비 중 예산 소진" 경로(`budget_exhausted_after_build`)는 타이밍 의존적이라 회귀 테스트를 만들지 않았다(스텁으로 `buildRequest` 지연을 안정적으로 재현하기 어렵다). 분류 변경(Unknown→Failed) 자체는 기존 스위치문이 `retryable` 플래그를 그대로 소비하므로 코드 경로는 검증됐다.

전체 재빌드 확인(REVISE 후): `./gradlew build`(Redis 기동 상태, `docker start slack-lab-redis-1`) → BUILD SUCCESSFUL. `./gradlew test --rerun`도 별도로 통과 확인.

### 10.2 code-reviewer 재검토 대응 (2026-09-29, codex 2회전이 사용량 제한으로 실패해 code-reviewer 에이전트가 대신 검토, MAJOR 1건·MINOR 1건·LOW 2건 반영)

§10.1의 MAJOR-1 수정(`preserve_at()`에 `next_gen`을 미리 섞은 배열을 한 번의 `HSET`으로 쓰기) 자체가 **같은 부류의 새 경합을 하나 더 열었다**는 것을 `redis-cli EVAL`로 직접 재현해 확인했다.

| 판정 | 위치 | 문제 | 수정 |
|---|---|---|---|
| MAJOR-A | `state/state.lua` `preserve_at()`(→ `retry` op) | `preserve_at()`은 보존 `HSET`을 먼저 쓰고 목록 `ZADD`를 나중에 쓴다. `retry` op에서 `HSET`(`gen=next_gen`)만 끝나고 `ZADD` 전에 끊기면: 재시도 목록엔 `event_id`가 올라가지 않아 `retry_scheduler.lua`가 이 항목을 영영 보지 못한다. 실제 운영에서는(§10.1 MAJOR-1의 재현 테스트와 달리) `EventWorker.finalizeResult()`가 `scheduleRetry()` 예외를 잡지 않고 그냥 로그만 남긴 채 ACK를 보류한다 — 즉 같은 attempt로 재호출하지 않는다. 원본 스트림 메시지는 임대 만료 뒤 재선점돼 old_gen으로 정상 완료(`COMPLETED`)된다. 이때 `cleanup_preserved_if_same_or_past_gen(old_gen)`이 "보존 gen(next_gen) > 완료 gen"을 미래 세대 보호로 오판해 청소하지 않아, 재시도·DLQ·복구 목록 어디에도 없이 `slack:preserved:{id}` 해시만 영구히 남는다(B18 위반) | `preserve_at()`에 `hset_first` 파라미터를 추가했다. DLQ·복구용 `preserve()`는 그대로 `HSET`을 먼저 쓴다(그 경로는 이 호출 직후 `ACK`로 원본이 사라지므로 입력 보존이 우선이고, 재전달이 같은 `claim()`/`finalize()` 판단을 그대로 다시 타 `preserve()`를 멱등하게 재호출해 목록 등록까지 마친다 — E24 등 기존 M11 테스트가 이 전제를 검증한다). `retry` op만 `hset_first=false`로 `ZADD`를 먼저 쓴다 — `retry`는 이 전제가 깨진다(재호출 없이 원본이 다른 시도로 넘어가 성공할 수 있다). `ZADD`만 끊기면 보존 해시가 아직 없어(또는 손대지 않아) 원본이 old_gen으로 완료될 때 `finalize`의 `cleanup_preserved_if_same_or_past_gen`이 "보존 없음"으로 판단해 목록 항목까지 즉시 함께 지운다 — 스케줄러 개입 없이도 잔존 0 |

**두 경로(DLQ·복구 vs 재시도)가 반대 순서를 써도 안전한 이유**(검증 근거): DLQ·복구는 `preserve()` 호출 자체가 그 op(`claim`·`finalize`)의 종료 처리이고, 곧이어 `ACK`로 원본 스트림 항목이 사라진다 — 그 시점부터 보존 해시가 입력의 유일한 사본이 되므로, `HSET`을 먼저 써 입력을 절대 놓치지 않는 쪽이 우선이다. 부분 실패 시엔 `ACK`도 안 됐으므로 재전달이 **같은 결정론적 판단**(상태만으로 정해지는 `claim()`/`finalize()` 분기)을 다시 내려 `preserve()`를 멱등 재호출하고 `ZADD`까지 마친다(§ M11 원칙, E21·E24·E25로 기존 검증됨). 반면 `retry`는 부분 실패 뒤에도 원본 스트림 항목이 그대로 살아있지만(아직 `ACK` 안 됨), 그다음 그 항목을 처리하는 것은 `retry` op의 재호출이 아니라 `claim()`이 새로 부르는 **핸들러**다 — 이번엔 성공해서 old_gen으로 그대로 `COMPLETED`될 수 있다. 이 경우 입력은 이미 스트림 항목 자체로 안전하므로(아직 `ACK` 전), `HSET`을 서둘러 먼저 쓸 필요가 없고 오히려 `ZADD`(목록 등재)를 먼저 써야 "목록에 없으면 보존도 없다"는 대칭이 유지돼 `finalize`의 정리 로직이 고아 없이 청소한다.

새 회귀 테스트: `RedisProcessingStateStoreTest.재시도_보존_HSET만_남고_ZADD가_비면_원본이_정상_완료될_때_보존본도_함께_치워진다` — `retry` op의 첫 쓰기(`ZADD`)만 성공하고 `HSET`(보존) 전에 끊긴 뒤, **재호출 없이** 원본 메시지가 임대 만료 → 재선점 → old_gen 정상 완료까지 실제 운영 경로 그대로 흘러가는 것을 확인한다(잔존 0: 보존 해시·DLQ·복구·재시도 목록 전부). 기존 `재시도_예약이_상태_기록_전에_끊기면_...`(구 E41, `ZADD`+`HSET` 둘 다 끝난 뒤 상태 기록 전에 끊기는 지점)도 순서 무관하게 동일한 최종 상태이므로 여전히 통과함을 확인했다 — 다만 주석의 "ZADD로 목록엔 이미 올라감" 표현을 새 순서에 맞게 정정했다.

**정정(MINOR-B, §10.1의 과장 시정)**: §10.1 새 회귀 테스트 설명 중 "`retry` op의 각 쓰기 지점마다 `fail_after` 주입 후 재호출/재선점을 거쳐도 gen 일치·잔존 0 확인"이라는 문구는 실제 검증 범위보다 넓게 읽혔다 — 그 테스트(`재시도_예약_부분_실패_뒤_다시_호출하면...`, 구 E40)는 **재호출**(같은 attempt_id로 `scheduleRetry`를 즉시 다시 부르는 합성 경로)만 검증했지, 실제 운영 경로(재호출 없이 원본이 재전달·완료되는 흐름)는 `failAfter=2`(구 E41) 한 지점만 커버했다 — `failAfter=1`(MAJOR-A가 재현된 바로 그 지점)은 다루지 않았다. 이번에 추가한 테스트가 그 공백을 메운다. 검증 범위를 정확히 좁히면: **재호출(합성) 경로는 모든 쓰기 지점에서 멱등 완결을 확인했고, 재호출 없는 실제 운영 경로(재전달·완료)는 두 위험 지점(`ZADD`만 성공/`ZADD`+`HSET` 모두 성공, 상태 기록 전)에서 잔존 0을 확인했다.**

**MINOR-C(재확인)**: `RetryPolicy.scheduleRetry()`의 `backoffMs.get(currentRetries)`는 `StartupInvariants`(`retry.backoff-ms 항목 수 >= retry.max-retries`)가 Spring 기동 경로에서는 막지만, 그 검사를 거치지 않는 조합(빈을 직접 생성하는 테스트 등)에서는 여전히 `IndexOutOfBoundsException`에 실제로 도달함을 code-reviewer가 `retry.max-retries=4`·기본 `backoffMs`(3개) 조합으로 재현했다 — §10.1 MINOR-4의 "실제 핸들러 경로로는 도달 불가"는 이 경계 조건까지 포괄한 주장은 아니었다. `RetryPolicy.scheduleRetry()`에 `Math.min(currentRetries, backoffMs.size() - 1)` clamp를 추가해 이중 방어했다(불변식이 있어도 설정 실수로 크래시하지 않게). `EventWorkerTest`의 관련 테스트를 clamp 확인으로 다시 쓰고, 이 경계 조합을 직접 재현하는 테스트(`StartupInvariants를_우회하는_설정_조합에서도_clamp가_크래시를_막는다`)를 추가했다.

**LOW 반영**: (1) `ARCHITECTURE.md` §3.4·`state.lua`의 "Lua 스크립트 실행 중간에 스케줄러가 끼어든다" 서술을 "Lua는 원자적이라 끼어들 수 없고, 부분 실패로 중단된 뒤 상태가 남는 것"으로 정정. (2) `retry_scheduler.lua`의 "이미 다른 경로로 끝난 항목" 정리 분기에서 `DEL`(보존 해시)을 `ZREM`(목록)보다 먼저 쓰도록 순서를 바꿨다 — 반대 순서면 그 사이에 끊겼을 때 목록에서 지워졌는데 해시만 남아 다음 주기에도 다시 보지 못한다. (3) 옛 테스트 이름·건수 언급(LOW-5)은 이번 작업 범위에서 직접 만지지 않은 문서라 남겨둔다(알려진 문제).

**스킵(문서화만)**: LOW-2(Slack/LLM 예산 소진 분류 불일치)·LOW-4(취소 테스트가 실제 취소를 증명 못 함)·LOW-6(스케줄러 배치 제한의 head-of-line blocking)은 code-reviewer 권고대로 이번엔 건드리지 않았다.

전체 재빌드 확인: `./gradlew build`(Redis 기동 상태) → BUILD SUCCESSFUL.

## 11. 2단계 M14 결과 불명 복구 검증 (2026-09-30)

조건: 호스트 `bootRun`(역할 `all`) + `docker slack-lab-redis-1`, Ollama `qwen2.5:7b`, 테스트 채널 1개. 요청은 서명이 유효한 **합성** `event_callback`이고(Slack→ngrok 구간은 아님), 답글은 **실제 Slack 스레드**에 달렸다. 스레드 조회를 위해 실제 부모 메시지를 `chat.postMessage`로 먼저 올렸다. 스코프 `channels:history`·`reactions:write`는 `auth.test`의 `x-oauth-scopes` 헤더로 이미 반영됨을 확인했다(멈춤 지점 해소). event_id·채널 ID는 기록하지 않는다.

| 시나리오 | 절차 | 결과 |
|---|---|---|
| **B5** 발신 직후 중단 | `--experiment.halt-after-send=true`로 기동 → 이벤트 1건 → LLM 성공, Slack 발신 성공 직후 `Runtime.halt` → 상태 `SENDING`, 메시지 pending | 서버 재기동 후 약 95초(`claim-min-idle-ms=100s`) 뒤 `XAUTOCLAIM` 회수 → 결과표 4행 `SETTLED:UNKNOWN`, `stage=sending_lease_expired`, 복구 목록에 보존. 재발신 0회 |
| B5 `check` | `scripts/recovery check <id>` | 스레드에서 `metadata` 일치 답글 발견, **`attempt_id`가 halt된 그 시도와 일치** |
| B5 해결 | `resolve-completed <id> <ts>` | `COMPLETED`(ts 기록), 보존 해시·목록 삭제, TTL 약 7일 |
| **미전송 → `reprocess`** | `slack.base-url`을 요청을 받고 연결을 끊는 스텁으로 → `UNKNOWN(answer_send:IOException)`(스텁 접속 1회 = 자동 재발신 0회). `check`는 "답글 없음(스레드 전체 확인)". 서버를 내리고 `confirm-unsent` 없이 실행하면 거절(종료 코드 2), 붙이면 승인 | 실제 Slack으로 재기동 → gen 1, `manual_run=1`로 1회 실행, 답글 발신, `COMPLETED`, 보존본 삭제 |
| **B11** 24시간 초과 | 25시간 전 `received_at`으로 스트림에 직접 `XADD` | 선점 결과 `SETTLED:EXPIRED` → `DEAD(window_expired)`, DLQ 보존, **발신 0회**. `reprocess` 승인 후 1회 실행 → `COMPLETED`, `manual_gen` 소비 |
| **B18** | `scripts/p1-residue-check` | 실행마다 `OK: 잔존물 0`(해결 8건, 미해결 0, 보존 해시 0, 스트림 잔존 0). 위반 주입(보존 해시·스트림 본문 잔존, TTL 없는 CLOSED, 보존 없는 목록 항목)에서는 5건을 모두 잡고 종료 코드 1 |

통합 테스트(Testcontainers, `RecoveryStoreTest` 17건): resolve·close 정리와 멱등, 다른 ts 거절, 진행 중 건 거절, **resolve의 쓰기 1~6번째 뒤 실패 주입 → 재실행으로 잔존 0**, **reprocess의 쓰기 1~4번째 뒤 실패 주입 → 재실행해도 gen은 한 번만 오르고 승인 실행은 정확히 1건**, 승인 실행 소실 시 4'행으로 `DEAD` 후 새 승인은 gen+1, 창 초과 건의 자동 차단과 수동 승인 실행. 전체 빌드 205건 통과.

발견·주의: (1) `--`로 시작하는 CLI 인자는 Boot가 옵션으로 파싱해 비옵션 인자에서 빠진다 → 확인 인자를 위치 인자 `confirm-unsent`로 했다. (2) `gradlew bootRun`은 CLI의 종료 코드 1·2를 빌드 실패로 출력해 `scripts/recovery`는 부트 jar를 직접 실행한다. (3) `docker exec -i`가 셸 반복문의 표준 입력을 삼켜 `p1-residue-check`가 첫 키만 보고 끝나던 버그를 실험 중 발견해 `</dev/null`로 고쳤다. (4) 개발 중 남아 있던 옛 DLQ 2건(`channel_not_found`)은 `close`로 정리했다. (5) 실제 사람 멘션(Slack→ngrok) 경로는 이 마일스톤에서도 검증하지 않았다. (6) 승인 실행 소실(4'행)은 통합 테스트로만 확인했고 실제 kill 실측은 없다.

### 11.1 리뷰 대응 (codex 1회전 REVISE → 반영, 2회전은 usage limit으로 code-reviewer 대체 APPROVE)

- codex 1회전: MAJOR 1(잔존 검사기가 Redis 실패를 `OK`로 보고 — `REDIS_CLI=false`로 재현) + MINOR 2(스트림 본문 속 `event_id` 줄 오인, `has_more`인데 커서 없는 스레드 조회를 완료로 판정). 모두 수정: 조회 실패는 종료 코드 3(검사 불가), 스트림은 JSON 짝 단위 파싱, 커서 없는 `has_more`·`messages` 누락은 불완전/실패. 오탐·미탐을 독립 Redis에 재현해 확인.
- 2회전(code-reviewer): **APPROVE, MAJOR 0**. Lua 새 경합·유실·중복 실행 경로 없음(승인은 8행에서 한 스크립트로 소비, 소실은 4'행). 이 중 MINOR m1(redis-cli는 오류 응답에도 종료 코드 0 → 대문자 오류 접두어 감지)·m2(mktemp 실패)·m3(스트림 JSON을 stdin으로)·m7(비어 있지 않은 커서도 "더 있음")는 반영했다.
- **알려진 한계(수정하지 않음)**: (m4) 승인됐지만 아직 선점 안 된 건(`RETRY_WAIT`+`manual_gen`)은 `close`로 철회할 수 없다. (m5) 4행 `UNKNOWN` 직후 옛 워커의 HTTP 발신이 아직 진행 중일 수 있어, 그 사이 `check`가 "없음"이라 `reprocess`하면 중복 답글이 날 수 있다 — 사람이 `send-deadline`(10초) 이상 지난 뒤 확인해야 한다. (m6) `ANOMALY`로 올라간 DLQ 항목 중 상태가 `PROCESSING`·`CLOSED`이거나 상태 해시가 만료된 것은 CLI로 지울 수 없다. (m7) 메타데이터에 `kind`가 없어 `check`가 실패 안내 답글도 "전송됨"으로 보여준다.

## 12. 2단계 M15 즉시 반응 검증 (2026-09-30)

조건: 호스트 `bootRun`(역할 `all`, 수신·워커·반응 소비자 한 프로세스) + `docker slack-lab-redis-1`, 합성 서명 요청 + 실제 부모 메시지(§11과 같은 방식). `reaction_ms`는 수신 필터가 잡은 `received_at`에서 `reactions.add` 성공까지이며 같은 호스트 시계다. 검증 환경이 PRD §5의 장비·부하 조건 전체와 같지는 않다(개발 장비 단일 호스트, 표본 12건).

| 시나리오 | 절차 | 결과 |
|---|---|---|
| **(a) 처리 적체** | `experiment.slow-mode-ms=20000`으로 처리를 막고 12건을 연속 투입(이벤트 12건 pending, 처리 완료 0) | 12/12 반응 성공, `reaction_ms` 오름차순 244·244·245·246·249·253·254·254·257·271·290·467 → **p95 ≈ 467ms(목표 ≤ 3s)**, 누락 0, 반응 스트림 잔존 0 |
| **(b) 반응 소비자 지연** | `reaction.experiment-delay-ms=8000` | 발신 성공·처리 종료가 12:02:09, 반응은 그 뒤 12:02:15에 붙음(`reaction_ms=8313`). 반응 스트림이 처리 그룹의 XDEL과 독립이라 **답변이 먼저 끝나도 누락 없음**, 잔존 0 |
| **(c) 반응 오류 유도** | `reaction.emoji=no_such_emoji_zz` → `invalid_name` | `반응 실패(재시도 안 함)` 로그 1회, 호출 1회. **답글은 정상** 발신·`COMPLETED`(총 1663ms). *스코프 오류 자체는 Slack 앱 화면에서 스코프를 빼야 재현되어 하지 않았다 — 같은 실패 경로(비정상 `ok:false`)를 다른 사유로 검증한 것이다* |
| 소비자 회수 | 10건 실험 중 첫 요청에서 Redis 명령이 1.2초 정체(`QueryTimeoutException`, 발행 503 1회) | 그 반응 항목이 소비자에게 전달만 되고 처리되지 못했으나, 죽은 소비자 회수(min-idle 10초)로 `reaction_ms=13539`에 처리됨. **이 1건은 3초 목표를 넘겼다** — 정체 없는 재측정(위 (a))에서는 재현되지 않았다 |

통합 테스트(`ReactionConsumerTest`): 성공·`already_reacted`·스코프 오류 모두 항목 삭제·호출 1회, 죽은 소비자 항목 회수, 처리 스트림 삭제와 무관한 반응 항목. `EventPublisherTest`: 반응 항목이 본문 없이 `event_id`·`channel`·`ts`·`received_at`만 담김. 전체 빌드 통과.

발견·주의: (1) **기한 취소가 `ExecutionException(CancellationException)`으로 도착하면 `OpenAiCompatibleLlmClient`가 `TimedOut`이 아니라 영구 `Failed`로 분류**하던 실제 경합을 잡았다(M14 중 `기한을_넘기면_취소되고_TimedOut을_반환한다` 간헐 실패의 원인, 재시도 대신 즉시 안내로 가는 결과). 수정 후 6회 연속 통과. (2) 정체 원인은 규명하지 못했다 — 첫 요청 지연은 3회 재기동으로 재현되지 않았다(enqueue 15~56ms). 발행은 실패해도 Lua가 이미 두 스트림에 썼을 수 있어 Slack 재전송이 오면 같은 event_id가 다시 들어오지만, 선점 결과표가 하나만 실행시킨다. (3) 사람이 실제로 멘션하는 Slack→ngrok 경로는 이 마일스톤에서도 검증하지 않았다.

### 12.1 리뷰 대응 (codex는 usage limit이라 code-reviewer 대체, APPROVE·MAJOR 0·MINOR 6)

반영: (1) 반응 읽기를 1건 단위로 줄여 뒤쪽 항목이 회수 min-idle을 넘겨 이중 처리되는 경로를 없앴다. (2) 명령 타임아웃이 나면 다음 반복에서 자기 PEL(`ReadOffset 0`)을 한 번 읽는다 — 위 실측의 13.5초 방치를 없애기 위한 것이다. (3) 종료 중 인터럽트로 `interrupted` 결과가 오면 ACK하지 않고 PEL에 남긴다. (4) 루프가 `Throwable`을 잡아 로그를 남긴다(조용한 실패 방지). (5) `publish.lua`의 "원자성" 주석을 실제 보장(끼어들기 없음, 롤백 없음)에 맞게 정정. 반영 후 정상 설정으로 재기동해 3건 스모크: `reaction_ms` 1526(첫 호출 웜업)·408·285, 전부 성공, 잔존 0. 위 (2)의 자기 PEL 재읽기는 통합 테스트로 만들지 않았고 실측으로도 유도하지 않았다(Redis 정체를 재현하기 어렵다).

알려진 한계(수정하지 않음): 반응 스트림에 `MAXLEN` 상한이 없다(소비자가 하나도 없으면 무한히 자란다 — residue-check가 10분 뒤 경고), 재기동마다 새 소비자 이름이 그룹에 남는다, `reaction_ms`는 Slack 재전송으로 들어온 항목이면 그 전달의 `received_at` 기준이라 최초 수신 기준보다 짧게 잰다, 회수는 PLAN의 `XAUTOCLAIM`이 아니라 `XPENDING`+`XCLAIM`이다(Spring Data Redis 3.4.1에 전용 API가 없다, `EventWorker`와 같은 방식), `ExecutionException(CancellationException)` 분류 수정의 회귀 테스트는 없다(경합이라 안정적 재현이 어렵다).

## 13. 2단계 M16 스레드 문맥 검증 (2026-09-30)

조건: 호스트 `bootRun`(역할 `all`), Ollama `qwen2.5:7b`, 합성 서명 요청 + 실제 스레드(§11·§12와 같은 방식). 스레드의 첫 메시지는 봇 계정으로 올린 "이 스레드의 비밀 단어는 '바나나'입니다"이다(사람 계정으로는 올릴 수 없어 조회 상 assistant 역할로 들어간다 — 역할 매핑은 단위 테스트로 따로 검증). 질문은 "이 스레드의 비밀 단어가 뭐였지? 한 단어로만 답해줘."이다.

| 시나리오 | 결과 |
|---|---|
| **문맥 조회 성공** | `스레드 문맥 context_messages=1 context_chars=36 fetch_ms=468` → LLM 1342ms → 답글 **"바나나"**(스레드의 이전 내용을 반영). 총 소요 2078ms |
| **조회 실패 유도** (`context.fetch-deadline-ms=1` → `deadline_exceeded`) | `문맥 없이 진행` 경고 로그 1회, LLM 529ms → 답글 **"비밀"**(문맥이 없어 앞 대화를 알지 못함), `Delivered`·총 799ms. 답글은 정상 발신됐고 조회 실패가 처리를 막지 않음. 문맥 유무에 따라 답이 갈리는 대조가 된다 |

단위·통합 테스트: `SlackThreadContextTest` 10건(역할 매핑·이번 메시지 제외·봇 멘션 제거·빈 메시지 버림·최근 N개·글자 한도·단일 메시지 초과 시 자르기·`ok:false`·기한 초과 시 기한 안에 포기·남은 예산이 더 짧으면 그 예산 사용·예산 0이면 조회 안 함), `SlackEventHandlerTest`(스레드면 이전 대화가 이번 질문 앞에 붙음, 스레드가 아니면 조회 안 함, 조회가 비어도 정상 답변), `OpenAiCompatibleLlmClientTest`(시스템 프롬프트 뒤에 user/assistant 순서 유지). 전체 빌드 통과.

주의: (1) 조회 실패 시연은 스코프 제거 대신 기한을 1ms로 줄여 유도했다(스코프를 빼려면 Slack 앱 화면 조작이 필요). (2) 사람이 실제로 멘션하는 Slack→ngrok 경로와 사람 계정 메시지의 `user` 역할 매핑은 실환경에서 검증하지 않았다 — 봇 메시지 판별을 `bot_id` 유무로만 하므로 다른 봇의 메시지도 assistant로 들어간다. (3) 대조군 이벤트(스레드 밖 질문)는 서버를 내리는 순간과 겹쳐 처리되지 않은 채 큐에 남았다가 다음 기동 때 처리된다.

### 13.1 리뷰 대응 (codex usage limit → code-reviewer 대체, 1회전 REVISE: MAJOR 2·MINOR 7)

- **MAJOR-1(반영)**: `conversations.replies`는 조회 시점의 스레드 전체를 주므로 큐 지연·재시도 사이에 올라온 *뒤 메시지*가 "이전 대화"로 섞였다. 현재 `ts` 이상인 메시지를 걸러낸다(소수 문자열이라 `BigDecimal`로 비교 — 문자열 비교면 `"1000.0" < "300.0"`이 된다). 회귀 테스트 추가.
- **MAJOR-2(반영)**: JDK `HttpRequest.timeout`은 응답 헤더까지만 막아 본문이 멈추면 3초 기한이 깨지고 60초 상한(규칙 10)까지 위협했다. `sendAsync` + `future.get(기한)` + `cancel`로 바꿨고, 헤더 후 본문이 멈추는 스텁 테스트로 기한 안에 포기함을 확인.
- **MINOR-1·2(반영)**: 기동 후 첫 조회에서 `auth.test`로 자기 `bot_id`·`user_id`를 알아 캐시한다. 자기 봇 메시지만 assistant로 넣고 **다른 앱의 봇 메시지는 버린다**(assistant로 넣으면 모델이 자기 말로 믿는 프롬프트 주입 경로). 문장 중간의 봇 멘션도 식별한 ID로 지운다. `auth.test`가 실패하면 예전처럼 모든 봇 메시지를 assistant로 본다. 수정 후 실서버 스모크(비밀 단어 '포도') 정상.
- MINOR-4(`llmBudgetMs` 이중 계산)·MINOR-5(옛 Javadoc)도 반영.
- **알려진 한계(수정하지 않음)**: 재시도할 때마다 스레드를 다시 조회한다(시도마다 최대 3초·Slack 호출 1회, 예산은 시도별 `t0`로 초기화되어 상한은 안전), 실패 안내(`FAILURE_NOTICE`)가 assistant 문맥으로 들어가 말투를 따라 할 수 있다(`ReplyMetadata`로 거르는 개선 여지), Slack이 Marketplace 밖 상용 앱에 `conversations.replies`를 분당 1회·15건으로 제한한 정책이 있다(내부용 앱이라 해당 없음, 배포 형태가 바뀌면 재확인), 글자 자르기는 UTF-16 단위라 이모지 서로게이트 쌍이 갈라질 수 있다, `fetchMessages`의 다중 페이지 경계(`missing_cursor`·`too_many_pages`) 전용 테스트는 없다.

## 14. 2단계 M17 관측 + 다중 워커 검증 (2026-09-30)

조건: `docker compose --profile app up --scale worker=2`(수신 1·워커 2·반응 1 컨테이너 + Redis 컨테이너), 호스트 Ollama `qwen2.5:7b`, 합성 서명 요청 + 실제 스레드(§11~13과 같은 방식). 워커는 `worker.concurrency=1`이다. 검증 환경은 PRD §5의 성능 판정 조건이 아니라 정확성 확인용이다.

### 14.1 집계 명령 (B14)

`scripts/p1-metrics <로그...>` 또는 `docker compose logs --no-log-prefix receiver worker reactor | scripts/p1-metrics`. 구간별 건수·p50·p95(정렬한 표본의 `ceil(0.95×N)`번째)·max, 결과별 건수, 발행·반응 실패 건수, 음수 구간 건수, 적체(스냅샷 횟수·최대 `stream_len`·최대 `pending`·마지막 `retry`/`dlq`/`recovery`)를 낸다. 답변 지표는 정상 답변(`Delivered`·`kind=answer`)만 센다. 실행 예(버스트 5건, 컨테이너 로그): `recv_ms` p50 464·p95 498, `enqueue_ms` p50 200·p95 207, `queue_wait_ms` p50 3106·p95 5331, `llm_ms` p50 2429·p95 3167, `send_ms` p50 296·p95 664, `answer_ms` p50 5318·p95 8404, `reaction_ms` p50 3132·p95 3356, 정상 답변 5/5, 발행·반응 실패 0, 음수 구간 0, 적체 스냅샷 14회(`stream_len`·`pending` 최대 1).

### 14.2 다중 워커 (B15)와 kill (B3·B4)

| 시나리오 | 절차 | 결과 |
|---|---|---|
| **D1** 동일 event_id 10회 동시 전달 | 워커 2개 | 10건 모두 200·큐 저장, 실행 1회, 나머지 9건 `BUSY`(ACK 안 함 → 이후 회수에서 정리, 최종 `pending 0`·`stream_len 0`), **답글 1개** |
| **D3** 서로 다른 10건 | 워커 2개, 10건 동시 투입 | **답글 정확히 10개**, 시도 10건 각각 별개의 `attempt_id` |
| **B3** 큐 저장 후 수신 kill | 200(큐 저장 확인)을 받은 직후 `docker kill -s KILL`로 수신 컨테이너 종료 | 이벤트는 큐에 남아 워커가 처리 |
| **B4** 처리 중 워커 kill | `EXPERIMENT_SLOW_MODE_MS=30000` 오버라이드로 처리를 느리게 → `XPENDING`으로 소유 소비자를 찾아 그 워커 컨테이너를 kill | 임대(30초) 만료와 `claim-min-idle-ms`(100초) 뒤 **남은 워커가 회수**(`죽은 소비자 항목 회수 stale_count=1`), 새 `attempt_id`로 재실행, **최종 답글 1개**, 138초, `queue_wait_ms=103158` |

B3·B4는 한 이벤트에서 함께 확인했다(수신을 죽이고 이어서 처리 중 워커를 죽임). 전 과정 뒤 `scripts/p1-residue-check` → `OK: 잔존물 0`(해결 29건, 스트림·반응 스트림 잔존 0).

통합 확인: `SlackEventHandlerTest`에 `recordPhase` 전달 검증 2건(발신 게이트에 막히면 `send_ms` 미기록). 전체 빌드 통과.

### 14.3 관측 사항 (M18이 판단)

- 컨테이너 환경 버스트 5건에서 `recv_ms` p50 464ms(목표 p95 ≤ 200ms), `reaction_ms` p50 3.1s(목표 p95 ≤ 3s)가 관측됐다. 호스트 `bootRun`(§12)에서는 반응이 0.25~0.47s, 수신 15~56ms였다. 원인은 규명하지 않았다 — 컨테이너·Docker 네트워크 오버헤드, 같은 장비의 Ollama·합성 요청 생성 스크립트와의 자원 경합, 워커 2개·반응 소비자 3개(워커 2 + 반응 1)의 경합 후보가 있다. 이것은 성능 판정이 아니라 M18의 고정 조건 실험이 다룬다.
- `BUSY`로 끝난 중복 메시지는 ACK하지 않고 회수 주기(30초 스캔·100초 idle)에 정리된다. 정확성에는 영향이 없으나 중복 전달이 잦으면 pending이 그동안 쌓인다.
- 사람이 실제로 멘션하는 Slack→ngrok→수신 컨테이너 경로는 이 마일스톤에서도 검증하지 않았다.

### 14.4 리뷰 대응 (codex는 usage limit이라 code-reviewer 대체, REVISE: MAJOR 2·MINOR 7)

- **MAJOR-1(반영)**: `적체 스냅샷 실패` 줄을 스냅샷으로 오인해 집계가 `KeyError`로 죽던 것을 고쳤다(성공 줄만 스냅샷으로 세고 실패는 따로 센다).
- **MAJOR-2(반영)**: 발행 실패 1건이 발행자·컨트롤러 줄 때문에 경로에 따라 1~2건으로 세어지던 것을 컨트롤러 줄만 세도록 고쳤다.
- **MINOR-1(반영)**: 재시도·재처리 시도의 `queue_wait_ms`·`answer_ms`에는 앞선 시도와 백오프가 섞여 p95를 왜곡한다. 지표 줄에 `retries`·`manual_run`을 추가하고, 집계는 **첫 시도(gen=0, 수동 재처리 아님)만** 성능 표본에 넣으며 뺀 건수를 보고한다. MINOR-2(성공률 분모를 event_id별 마지막 결과로도 보고), MINOR-3(`recv_ms`는 `ack_delivered=true`만), MINOR-7(스냅샷 줄 수가 워커 수만큼 중복임을 출력에 명시), MINOR-4(`received_at`이 없거나 깨졌으면 `received_at_missing=true`로 표시하고 측정 무효로 취급), MINOR-6(적체 키 상수 재사용)도 반영.
- 합성 로그로 집계 회귀 확인(스냅샷 실패 줄·발행 실패 이중 줄·재시도 시도·`ack_delivered=false` 포함) 후 새 이미지로 3건을 다시 돌려 실제 컨테이너 로그 집계도 확인: 정상 답변 3/3, 음수 구간 0, 잔존물 0.
- **알려진 한계(수정하지 않음)**: 단계 이름(`llm_ms`·`send_ms`)이 문자열 키라 오타가 조용히 -1이 된다(상수화 여지), `answerMs < 0`은 같은 벽시계라 사실상 `queueWaitMs < 0`에 포함된다, 집계의 `kv` 파싱은 값에 공백이 들어가면 잘린다(현재 지표 줄에는 그런 값이 없다).
- 정정: 위 §14.3의 `reaction_ms` 3.1s는 버스트 5건 관측이었고, 부하가 낮은 3건 재측정에서는 p50 1.5s·p95 2.2s였다. 호스트 `bootRun`(0.25~0.47s)보다는 여전히 느리다 — M18이 판단한다.

## 15. 2단계 M18 P1 검증 실험 (2026-09-30)

**구분: 검증 수행 완료 = 예(P·D·R 세 실험 모두 수행). P1 합격 = 아래 판정 표대로, 단 §15.5의 한계 안에서.**

### 15.1 환경 (PRD §5 환경 고정)

| 항목 | 값 |
|---|---|
| 장비 | Apple M1(8코어), RAM 16GB, macOS 26.6.1 (§1과 같은 장비) |
| Ollama | 0.34.0, 호스트에서 실행(컨테이너 아님), 기본 설정(`OLLAMA_NUM_PARALLEL` 미설정) |
| 모델 | `qwen2.5:7b`, digest `845dbda0ea48…`, Q4_K_M, 7.6B, 컨텍스트 32768(요청은 §1과 같은 형식) |
| 출력 토큰 상한 | `llm.max-tokens=512`, `keep_alive=30m`, 온도 0.3(코드 기본값) |
| 추론 동시성 | 워커 `worker.concurrency=1`(M9 확정값) |
| 워커 수 | 성능 P: **1개**(모든 배치 동일). 중복 D: 1개와 2개 각각. 복구 R: R1 2개·R2 2개·R3 1개 |
| 컨테이너 | Docker Engine 29.3.1, VM 8 CPU / 약 8.5GiB. 수신·워커·반응·Redis(`redis:8.2-alpine`, 서버 8.2.10, AOF `always`) 컨테이너, Java 21 |
| 입력 | 같은 질문 세트 20개(고정 순서, `scripts/p1-load`), 짧은 답을 유도하는 한 문장 질문 |
| 요청 | 서명이 유효한 **합성** `event_callback`(실제 테스트 채널·실제 부모 메시지). Slack→ngrok 구간은 거치지 않음 |
| 429 | 실행 중 실제 429 0건(로그 확인) |

### 15.2 성능 P (워커 1개, 로그 집계 `scripts/p1-metrics`)

콜드 스타트(모델을 내리고 기동한 직후 첫 건): `llm_ms=7997`, `answer_ms=8374`; 두 번째 건은 `llm_ms=1792`, `answer_ms=2153`. 워밍업 2건은 표본에서 제외.

| | n | 수신 `recv_ms` p95 | 답변 `answer_ms` p95 | 유실·중복·실패 안내 | 판정 |
|---|---|---|---|---|---|
| (a) 순차 20건 | 20 | **88ms**(p50 46, max 131) | **9095ms**(p50 2935, max 24473) | 0·0·0 | 수신 ≤ 200ms ✓, 답변 ≤ 45s ✓ |
| (b) 버스트 5건×2 | 10 | **171ms**(p50 99, max 171) | 31230ms(p50 13080) — *적체 관측값, 판정 제외* | 0·0·0, 기한 초과 0 | 수신 ≤ 200ms ✓ |

세부(순차): `enqueue_ms` p95 27, `queue_wait_ms` p95 87, `llm_ms` p50 2602·p95 8045·max 23983, `send_ms` p95 953, `reaction_ms` p50 299·p95 408. 세부(버스트): `enqueue_ms` p95 106, `queue_wait_ms` p95 20708, `llm_ms` p95 10879, `reaction_ms` p50 951·p95 1642(B12 ≤ 3s ✓). 발행·반응 실패 0, 음수 구간 0, 적체 최대 `stream_len` 1(순차)·`pending` 1. 순차 `llm_ms` max 23983ms인 한 건은 모델 추론 편차로 보이며(원인 미규명) 45초 안에 들었다.

### 15.3 중복 D

| 실험 | 워커 | 결과 |
|---|---|---|
| D1 동일 event_id 10회 동시 | 1 / 2 | 10회 모두 200, **최종 답글 1개** / **1개** |
| D2 완료 뒤 재전달 1회 | 1 / 2 | 200, 10초 대기 뒤에도 **답글 추가 0개** / **0개** |
| D3 서로 다른 event_id 10건 동시 | 1 / 2 | **답글 정확히 10개**(각 스레드 1개, 유실·중복·실패 안내 0) / **10개** |

D1의 나머지 9건은 `BUSY`로 ACK하지 않고 회수 주기에 정리된다(실험 뒤 `pending 0`, `stream_len 0`). 실행 뒤 `scripts/p1-residue-check` → `OK: 잔존물 0`.

### 15.4 복구 R (성능 측정과 분리, 컨테이너)

| 실험 | 절차 | 결과 |
|---|---|---|
| **R1** 큐 저장 후 수신 kill | 200 수신 직후 `docker kill -s KILL` 수신 컨테이너 | 워커가 처리, 상태 `COMPLETED`, 답글 1개 (B3 ✓) |
| **R2** 처리 중 워커 kill | `EXPERIMENT_SLOW_MODE_MS=30000`, 워커 2개, `XPENDING` 소비자로 소유 워커를 찾아 kill | 남은 워커가 회수해 재실행, 새 `attempt_id`, **153초**(claim-min-idle 100s + 임대 + 재실행), 답글 1개 (B4 ✓) |
| **R3** 발신 직후 halt | `EXPERIMENT_HALT_AFTER_SEND=true` → 발신 성공 직후 컨테이너 종료(137) → 정상 설정으로 재기동 | 약 110초 뒤 회수돼 `UNKNOWN(sending_lease_expired)`·복구 목록 보존, 재발신 0회. `recovery check`가 metadata로 그 시도의 답글(`attempt_id` 일치)을 찾고 `resolve-completed`로 완료 → 잔존물 0 (B5 ✓) |

B11(24시간 자동 차단·수동 승인 1회)은 §11에서 실측했고, 이번 실행에서는 다시 유도하지 않았다.

### 15.5 판정과 한계

| 기준 | 판정 |
|---|---|
| B1(503)·B2(역할별 빈)·B8~B11(재시도·DLQ·승인)·B13(문맥) | 해당 마일스톤(§8~§13)에서 수행. 이번 M18에서 재실행하지 않음 |
| B3·B4·B5 | ✓ (R1·R2·R3, 컨테이너) |
| B6·B7·B15 | ✓ (D1~D3, 워커 1·2개) |
| B12 | ✓ 순차 p95 408ms·버스트 p95 1642ms(≤ 3s), 누락 0. 적체·답변 선완료·오류 유도는 §12 |
| B14 | ✓ (`scripts/p1-metrics`, §14) |
| B16 | ✓ 순차 20: 수신 p95 88ms·답변 p95 9.1s, 버스트 5×2: 수신 p95 171ms, 유실·중복·실패·기한 초과 0 |
| B17 | ✓ `grep`로 `event/` 패키지의 HTTP import 0건 |
| B18 | ✓ 각 실험 뒤 `p1-residue-check` 잔존물 0 |
| B19 | ✓ `./gradlew build` 통과 |

**한계(합격 주장의 범위)**: (1) 요청이 합성이라 **사람이 실제로 멘션하는 Slack→ngrok→수신 구간은 M9~M18 어디서도 검증하지 않았다** — "수신 p95"는 서버 HTTP 진입부터 응답 완료까지이며 ngrok·Slack 구간은 포함하지 않는다. (2) 표본이 작다(순차 20·버스트 10). 버스트 수신 p95는 171ms로 한도(200ms)에 근접해 여유가 크지 않고, M17 소규모 버스트에서는 p50 464ms까지 나온 적이 있다(부하 생성 스크립트·Ollama와 같은 장비 경합 후보, 원인 미규명). 각 실험은 한 번씩만 실행했다. (3) 중복 억제 보장 범위는 `COMPLETED` 보존 7일이다(PRD §5) — 7일 뒤 같은 event_id가 다시 오면 새로 실행된다. (4) 죽은 워커 회수에는 최대 약 100초가 걸린다(R2 153초). (5) 코드 리뷰는 M9~M17 각 마일스톤에서 거쳤고, M18의 산출물은 부하 생성기(`scripts/p1-load`)와 문서뿐이라 별도 리뷰를 하지 않았다.

## 16. 2단계 후속 M19 포트/어댑터 분리 검증 (2026-10-02)

목적: 큐·저장소·LLM·채팅 서비스를 인터페이스(포트) 뒤로 옮겨 코어가 구현체를 모르게 한다(ADR-9). **동작은 바꾸지 않는다.**

| 확인 | 결과 |
|---|---|
| 구조 | `core/{model,port,service}` + `adapter/{redis,slack,llm,cli,web}` (ARCHITECTURE "패키지 구조"). `EventWorker`를 코어 `EventProcessor`(선점→처리→확정→ACK)와 `RedisStreamConsumer`(읽기·회수)로 분리 |
| 회귀 | 기존 232건 + `ArchitectureTest` 5건 + `EventProcessorTest` 11건, 전체 249건 통과 |
| 컨테이너 스모크 | 새 이미지로 `docker compose --profile app up`(수신·워커·반응·Redis), `/health` `{"status":"UP","redis":"UP"}`, `scripts/p1-load warmup` 2건 → 답글 2개(유실·중복 0), `scripts/recovery list` 정상, `p1-residue-check` OK. 워밍업 직후라 `llm_ms=11065`·`send_ms=2710`로 느렸지만 장시간 쉬다 처음 호출한 모델 적재 지연이다(코드 동작과 무관) |
| 아키텍처 규칙 | 코어→어댑터 금지, 코어의 인프라 라이브러리(Redis·Lettuce·Servlet·HTTP·Jackson·AMQP·JDBC·`javax.sql`·`HttpURLConnection` 등) 금지, 코어는 `config`의 `…Properties`·역할 표시만 참조, `core.model`은 JDK만, `core.port`는 모델만, 어댑터끼리 금지 |
| 변이 검사 | 코어에 `StringRedisTemplate`을 넣으면, `javax.sql.DataSource` 필드를 넣으면, 코어가 `config`의 비설정 클래스를 참조하면 각각 `ArchitectureTest`가 실패. 원복하면 통과 |

### 16.1 리뷰 대응 (codex 1회전 REVISE: MAJOR 2·MINOR 3)

- **MAJOR-1(상태 저장소 포트에 Redis식 계약이 샘)**: `ProcessingStateStore` Javadoc의 계약을 "상태를 기록하고 확정 여부만 알림, ACK는 호출자(코어)가 `QueueDelivery`로"로 고쳤다. 입력을 보존해야 하는 전이(결과 불명·DLQ·재시도)를 Postgres가 하려면 입력 본문이 필요하므로 `ClaimRequest`에 `input`(이벤트 전체)을 추가했다. Redis 구현은 스트림 항목이 입력 원본이라 쓰지 않는다. `deliveryToken`은 그런 구현이 쓰는 불투명 값이다. **`scheduleRetry`는 아직 Redis 방식(상태 저장소가 예약 목록까지 관리)이다** — 브로커가 지연 재발행을 직접 지원하는 M21에서 큐 포트의 지연 발행으로 옮기고 상태 저장소에는 전이만 남긴다(Javadoc과 ARCHITECTURE에 표시).
- **MAJOR-2(아키텍처 테스트 허점)**: 금지 목록에 `javax.sql`·`HttpURLConnection`·`jms`·`kafka`·AWS SDK를 추가하고 `config` 참조를 설정 값·역할 표시로 제한했다. 변이 검사로 확인.
- **MINOR-3(확정 뒤 추가 ACK 실패가 지표를 지움)**: `EventProcessor`가 ACK 예외를 잡아 경고만 남기고 지표 줄은 항상 남긴다. 상태는 이미 기록됐으므로 재전달 때 선점 결과표가 종료로 판정한다.
- MINOR-4(증빙 참조 없음)는 이 절로 해소, MINOR-5(미사용 import·`ACKDEL` 상수)는 전체 정리.
- **알려진 한계**: Redis 어댑터에서 `acknowledge()`는 상태 저장소의 Lua가 이미 ACK한 뒤 한 번 더 도는 멱등 호출이라 Redis 왕복이 한 번 늘어난다(M22에서 Redis 제거 시 사라짐). 사람이 읽기 쉽게 하려고 정리하지 않은 임시 타협이다.

### 16.2 2회전 리뷰 대응 (codex 사용량 한도 → code-reviewer 대체: REVISE MAJOR 1·MINOR 6, 동작 보존은 통과)

- **MAJOR-1(`QueueDelivery`가 "ACK하지 않으면 브로커가 다시 전달한다"는 Redis 가정을 계약으로 박음)**: RabbitMQ는 채널이 살아 있는 동안 ack하지 않은 메시지를 다시 주지 않고 prefetch 슬롯만 차지한다(consumer_timeout 기본 30분). `QueueDelivery.defer()`를 추가해, 코어가 확정하지 못했거나 지금 처리할 수 없는 모든 분기(BUSY·NoInput·선점 예외·발신 게이트 거절·종료 기록 거절/예외)에서 놓아주는 방법을 어댑터에 위임한다. Redis 구현은 no-op(pending+회수가 그 역할)이고 RabbitMQ 구현은 지연을 둔 재발행이어야 한다(M21 등록). RabbitMQ 어댑터 지침(같은 tag 이중 ack 시 채널 종료, 원래 채널로만 ack, `process`를 부른 스레드에서만 호출)을 Javadoc에 명시.
- **MINOR-1**: `claim()` Javadoc을 추가하고 `ClaimOutcome` 주석을 브로커 중립으로 바꿨다. `Settled` 전에 필요한 보존은 커밋까지 끝나야 하고, 보존할 입력이 없으면 `NoInput`.
- **MINOR-2(M20 시점에 Postgres 상태 + Redis 큐 배선에서 재시도 재투입 주체가 없음)**: PLAN M20에 "Postgres 상태 어댑터는 M21 전까지 운영 배선하지 않고 테스트 전용"으로 명시하고, M21에서 재투입을 코어(`RetryPolicy`)가 큐 포트 지연 발행으로 하는 것으로 정했다.
- **MINOR-3(코어의 ACK 책임을 지키는 테스트 없음)**: `EventProcessorTest` 11건. 가짜 저장소·가짜 전달로 확정/놓아주기 분기, `ClaimRequest.input` 채움, ACK 예외 격리, 종료 기록 예외 격리를 검증. `acknowledgeQuietly` 호출을 지우는 변이를 넣으면 실패하고 원복하면 통과.
- **MINOR-5(알람 입력 포트 없음)**: `AlertEvent`·`AlertNormalizer` 자리 포트 추가(M23에서 구체화).
- **MINOR-6(종료 기록이 예외로 끝나면 지표 줄 없음)**: `finalizeResult` 예외를 잡아 `finalized=false`로 지표를 남기고 놓아준다.
- LOW: `Enqueued(streamId)`→`messageId`, 통합 테스트 이름 `EventWorkerTest`→`RedisStreamConsumerIT`, `RedisStreamConsumer` 낡은 주석·중복 Javadoc 정리(회수 스케줄러는 스레드 1개), `ArchitectureTest` 금지 목록 확대(`redis.clients`·HTTP 클라이언트·JPA·netty·`URL`·`Socket`)와 `config` 허용을 **코어가 실제 쓰는 설정 값 이름 목록**으로 제한(브로커 전용 `QueueProperties`·`ReactionProperties` 차단).
- 정정/한계: 로그 키 `stream_id=`가 `delivery=`로 바뀌었다(스크립트가 grep하는 곳은 없음). Redis 어댑터의 추가 ACK 왕복 한 번(약 1ms)이 `answer_ms`에 더해진다.

## 17. LLM 언어 혼용 방어 (2026-10-02)

문제: Slack 답글에 중국어가 섞였다(사용자 보고). 요구: 한국어 + 영어(에러·동작 설명용)만 나오게.

**재현(Ollama `qwen2.5:7b`, 기술 장애 질문 12개, 구 프롬프트)**: 12건 중 1건("메모리 누수가 의심될 때 확인 방법")이 `专业的中文翻译如下…`로 시작하는 중국어 문자열 뒤에 한국어가 이어졌다. 한국어로 쓴 "한국어로만" 지시가 이미 있었는데도 샌다. 새 프롬프트(영어 지시 + 영어 허용 범위 명시 + 한자·가나 금지)로도 12건 중 1건이 `的重大问题请使用中文简体回答…`로 시작했다 — **프롬프트만으로는 막을 수 없다**.

**수정(2중)**: ① 시스템 프롬프트를 영어 지시 + 한국어 지시로 바꾸고 영어는 에러 메시지·예외/클래스명·로그·명령어·코드·설정 키·표준 기술 용어에만 허용. ② `OpenAiCompatibleLlmClient`가 응답에 한자(CJK)·일본어 가나가 한 글자라도 있으면 Slack에 보내지 않고, 남은 시간이 3초 이상이면 언어 재강조 시스템 메시지를 붙여 한 번 다시 묻는다. 다시 물어도 섞이거나 시간이 부족하면 `Failed("language_violation", retryable=true)`로 돌려 기존 재시도(5초·30초·120초)와 최종 안내 정책을 탄다.

**검증**: 단위 테스트(영어 기술 용어만 섞인 한국어는 재질문 없음, 중국어 → 재질문 → 정상 응답, 두 번 다 위반 → 재시도 가능 실패, 시간 부족 → 재질문 없이 실패, 문자 판별). 실제 스택에서 기술 질문 12개를 두 번씩(24건) 실제 Slack 스레드로 돌려 `scripts/p1-load`(`P1_QUESTION_SET=tech`)가 답글의 한자·가나를 검사: **언어 위반 0건**. 단 이번 실행에서는 방어 경로(재질문)가 한 번도 발동하지 않았다(로그 0건) — 위반이 확률적(재현 실험에서 12건 중 1건)이라 24건에서 나오지 않은 것으로, 라이브에서 방어가 작동하는 것은 확인하지 못했고 단위 테스트로만 확인했다. 그 24건 중 2건은 실패 안내가 나갔는데, **언어와 무관하게 사용자 노트북이 다른 작업으로 부하 상태였고 LLM 응답이 9~50초로 늘어 50초 기한(`cancelled_after_deadline`)을 넘긴 것**이다(언어 위반 아님).

한계: 한자를 한 글자라도 쓰면 위반이므로 한국어 답변에 한자어 병기(예: `漢字`)가 정상적으로 들어가도 막힌다(재질문 후 실패하면 안내로 간다). 방어는 한자·가나만 보며, 다른 외국 문자(키릴 등)는 보지 않는다.

## 18. 2단계 후속 M20 Postgres 작업 상태 어댑터 검증 (2026-10-02)

목적: Redis 구현(`state.lua`)의 선점 결과표·임대·세대·보존을 같은 의미로 Postgres에 옮긴다(ADR-9). **운영에는 아직 배선하지 않는다** — 재시도 재투입은 M21(큐 어댑터)에서 코어가 큐 포트의 지연 발행으로 하기로 했다.

구성: Flyway 마이그레이션 `V1__processing_state.sql`(`processing_state`, `preserved_input`), `PostgresProcessingStateStore`(JdbcTemplate + 트랜잭션), 전역 DataSource 자동 구성은 끄고 어댑터가 직접 만든다. 이벤트 단위 어드바이저리 락(`pg_advisory_xact_lock`)으로 같은 이벤트의 동시 전이를 직렬화한다(첫 선점처럼 행이 아직 없는 경합도 같은 락). "지금"은 DB 시계다(Redis `TIME`과 같은 이유).

Redis 구현과 달라진 점
- 한 연산이 한 트랜잭션이라 "검증 → 멱등 보존 → 상태 → ACK" 순서 규율과 `fail_after` 부분 실패 복구가 필요 없다. 중간 실패는 전부 롤백된다.
- 보존 입력은 이벤트당 한 행(`list_name`: dlq·recovery·retry)이라 Redis에서 옛 목록 멤버십이 남던 틈이 없다.
- 선점 때 받은 `ClaimRequest.input`을 상태 행에 보관해 두었다가 결과 불명·DLQ·재시도 전이에서 보존한다(포트 계약, M19).
- ACK는 이 저장소의 일이 아니다.

| 검증 | 결과 |
|---|---|
| 선점 결과표 | 새 이벤트, 소유자 아닌 전이 거절, 완료·닫힘 재전달(DONE), 같은 세대 UNKNOWN/DEAD 재전달(멱등 재보존)·이전 세대(보존 없음), 이전 세대·예약 전 재시도(STALE), 발신 중 임대 만료(UNKNOWN+복구 목록), 늦은 UNKNOWN→COMPLETED(보존 삭제), 임대 만료 후 재선점과 이전 소유자 거절, 갱신, 24시간 초과(DLQ), 우선순위 조합(만료 SENDING+24h→UNKNOWN, COMPLETED+24h→DONE, 이전 세대+24h→STALE), 수동 승인 1회 소비와 소실 시 DEAD(B11), ANOMALY, 재시도 예약·도래 후 선점·횟수 상속, 재시도 보존이 다음 세대 번호로 기록, 완료 시 보존 정리, 더 최근 세대 보존본 유지 |
| 동시성 | 10 스레드 동시 선점 → 1승·9 BUSY |
| 입력 보존 계약 | 보존이 필요한데 입력이 없으면 `NoInput`이고 아무것도 쓰지 않음, 입력 없이 선점하면 보존이 필요한 종료 전이 거절, 허용되지 않는 전이 거절, 자가 재완료 멱등 |
| 보존 기간 | 만료된 완료 건만 삭제, 미해결(UNKNOWN)은 유지 |
| 원자성 | SQL 중간에 실패를 주입(`finalize`·`claim`)하면 상태와 보존이 모두 원래대로이고, 같은 호출을 다시 하면 정상 종료 |
| **선점 지연** | 순차 200회 `claim` p50 **1ms**, p95 **1ms**, max 4ms (Testcontainers `postgres:16-alpine`, 로컬 컨테이너·단일 연결, 개발 노트북 — PRD §5 성능 판정 환경이 아니다). 수신 경로 밖(워커)이라 p95 200ms 목표와 무관하며 Redis(`XADD+WAITAOF` p95 3ms)와 같은 자릿수다 |
| 전체 빌드 | 테스트 36건 추가(리뷰 대응 포함), 전체 통과 |

발견·주의: (1) 임대 만료를 JVM `sleep`으로 기다리는 테스트는 Docker VM 시계와 어긋나면 흔들렸다(전체 빌드 중 1회 실패). 임대 만료는 DB에서 `lease_until`을 직접 과거로 돌려 결정적으로 만들고, 실제 시간 경과 만료는 한 테스트만 여유 있게 남겼다. **운영에서도 DB 시계가 판정 기준이므로 워커와 DB 시계가 크게 어긋나도 임대 판정은 DB 기준으로 일관된다.** (2) 스키마는 Flyway로 관리하며, 전역 자동 구성을 끄지 않으면 접속 정보가 없는 수신·복구 역할이 DataSource 생성에 실패한다.

한계: 미해결 목록 조회·`resolve`·`reprocess`·`close`는 아직 Redis 복구 저장소에만 있다(M22에서 `RecoveryStore`의 Postgres 구현). 재시도 재투입(스케줄러)도 M21.

### 18.1 리뷰 대응 (codex 사용량 한도 → code-reviewer 대체: REVISE MAJOR 1·MINOR 7·LOW 6)

- **MAJOR(B18 회귀: 종료된 건의 입력 본문이 상태 행에 남음)**: Redis는 상태 해시에 본문이 없고 `XACKDEL`이 스트림 항목을 지웠지만, Postgres는 `processing_state.input`에 선점 입력을 들고 있다가 종료 뒤에도 남겨 완료 건은 7일간 질문 본문이 DB에 있었다. 모든 종료 전이(`finalize`, 행 4·4'·7)에서 `input = NULL`로 비운다 — 본문은 `preserved_input`에만 있다. 테스트가 COMPLETED·UNKNOWN·DEAD(finalize)·UNKNOWN(행 4)·DEAD(행 7)·DEAD(행 4')에서 `input IS NULL`을 확인한다. `finalize`의 `input = NULL`을 지우는 변이를 넣으면 실패하고 원복하면 통과.
- **MINOR**: ① 이상 메시지(행 1)가 재시도 예약 보존본을 덮어 이벤트가 재시도 목록에서 사라지는 문제 → 이상 경로는 다른 목록의 보존본을 덮지 않는다(테스트). ② 락 대기 무한 → `SET LOCAL lock_timeout`·트랜잭션 타임아웃. ③ READ COMMITTED 의존 → 명시하고 이유를 주석으로. ④ 테스트 훅의 공유 가변 상태 → 스레드별 카운터. ⑤ 시간 의존 테스트 → 재시도 도래는 DB에서 `retry_at`을 직접 0으로, 임대 만료는 `expireLease`로 결정적으로 만들고 실제 시간 경과 케이스(발신 중 만료 → UNKNOWN)를 하나 남김. ⑥ 2'의 DEAD 경로·B11 보강(승인 세대 불일치는 소비·수동 실행으로 보지 않음, 24시간 뒤 승인 실행 소실도 DEAD) 테스트 추가.
- **LOW**: 행 8이 `stage`를 지우던 것을 이전 값 유지(Lua와 같게), `(Long) rs.getObject` → `getObject(col, Long.class)`. 입력 없는 선점은 `NoInput`으로 입구에서 막는다(입력 없이 실행하면 종료 전이가 보존할 입력이 없어 임대 만료 → 재선점을 24시간 창이 닫힐 때까지 되풀이한다).
- **알려진 한계(수정하지 않음)**: `application.yml`의 `spring.autoconfigure.exclude`는 같은 키를 설정하는 프로필·환경변수가 있으면 목록 전체가 교체돼 DataSource 자동 구성이 되살아난다. `purgeExpired`는 상태 행만 지우므로 미래 세대 이상 보존본이 고아로 남을 수 있다(Redis도 같음, M22에서 정리). `retryAtMs`는 호출자의 JVM 시계로 계산되므로 "시간은 항상 DB 시계"는 임대·창 판정에 한한다(M21에서 지연 길이를 넘기는 방식을 검토).

## 19. 2단계 후속 M21 RabbitMQ 큐 어댑터 검증 (2026-10-02)

구성: `adapter.rabbitmq`(`RabbitBroker`·`RabbitEventPublisher`·`RabbitConsumer`·`RabbitDelivery`), 코어에 `EventRepublisher`·`RetryOutbox`·`RetryRelay` 추가, Postgres 저장소가 `RetryOutbox` 구현(마이그레이션 V2 `relayed_at`). `queue.backend=rabbitmq`일 때만 켜지고 기본값은 아직 Redis다 — 전체 배선(Postgres 상태 + RabbitMQ + 반응 큐 + 복구 CLI)은 M22.

설계 결정
- **토폴로지**: 직접 교환기 `<큐>` → quorum 큐(`x-delivery-limit`, 초과분은 `<큐>.dead`로), 지연 큐 `<큐>.defer`(고정 TTL, 만료되면 원래 교환기로), 영속 메시지, 수동 ack, 채널마다 prefetch 1.
- **발행 확인**: mandatory + 퍼블리셔 컨펌. 브로커 ack일 때만 `Enqueued`, nack·미라우팅은 `Failed`, 시간 초과는 `Unconfirmed`(둘 다 수신 서버가 200을 주지 않음). 확인 대기는 `queue.enqueue-timeout-ms`.
- **`defer()`**: 지연 큐에 복사본을 넣고(확인 대기) 원본을 ack한다. 즉시 nack(requeue)하면 처리 중인 메시지가 빠르게 맴돌기 때문이다. 복사 실패 시 nack(requeue)하고 전달 횟수 상한이 무한 맴돌기를 막는다.
- **재시도 지연은 TTL 큐가 아니라 Postgres 발신함(outbox)**: 상태 `RETRY_WAIT`과 보존 입력이 곧 발신함이고 `RetryRelay`가 도래한 건을 재발행한다. 브로커와 DB를 한 트랜잭션으로 묶을 수 없어서, "상태에 먼저 기록 → 나중에 큐에 반영"을 되풀이 가능하게 했다. 순서가 어느 지점에서 끊겨도 입력이 사라지지 않는다(재발행 확인 전에 죽으면 다음 사이클이 다시 넣고, 확인 뒤 반영 기록 전에 죽어도 선점 결과표가 하나만 실행시킨다). TTL 큐는 지연이 큐 하나에 고정돼 5초·30초·120초 백오프를 표현하지 못한다.
- **소비자 타임아웃**: RabbitMQ 기본 `consumer_timeout` 30분이 LLM 처리 최대 50초보다 훨씬 길어 따로 맞출 필요가 없다. 줄이는 설정은 총 처리 시간(60초)보다 길게 유지해야 한다(Javadoc).

| 검증(Testcontainers RabbitMQ 3.13 + Postgres 16, 코어·핸들러는 실제, LLM·Slack은 가짜) | 결과 |
|---|---|
| 정상 왕복 | 발행 확인 → 소비 → 선점 → 발신 1회 → `COMPLETED` → 큐 비움 |
| 동일 event_id 10회 발행 | 발신 1회, 놓아준 중복이 지연 큐를 돌아와도 추가 발신 0, 큐·지연 큐 모두 비워짐 |
| **B4 처리 중 소비자 강제 종료** | 첫 워커의 연결을 강제로 끊음(핸들러는 막힌 채 임대 갱신 없음) → 브로커가 되돌림 → 둘째 워커가 BUSY→defer로 기다리다 **임대 만료 뒤 재선점**해 완료, 발신 1회. 죽었던 핸들러가 나중에 깨어나도 소유권이 없어 발신하지 못함 |
| 지연 재시도 | 첫 호출 시간 초과 → `RETRY_WAIT` 예약 → 릴레이가 다음 세대로 재발행 → 둘째 호출 성공, 발신 1회, 완료 뒤 재시도 보존본 정리 |
| 릴레이 되풀이 안전 | 브로커에 닿지 않으면 반영 시각을 남기지 않고(다음 사이클이 재시도), 확인되면 기록하고 이후 건너뜀 |
| 발행 실패 | 브로커에 닿지 못하면 `Failed`(수신 서버는 200을 주지 않음) |
| 독약 메시지 | 읽을 수 없는 본문은 데드레터 큐로 가고 소비자는 계속 동작. 소비자가 계속 예외를 던지면 전달 횟수 상한 뒤 데드레터 큐로 |
| **발행 확인 지연** | 순차 100회 p50 **1ms**·p95 **3ms**·max 5ms, 동시 5스레드×20 p50 3ms·p95 **16ms**·max 35ms (퍼블리셔 채널 하나에서 직렬화, 개발 노트북 로컬 컨테이너 — PRD §5 성능 판정 환경이 아님). 수신 p95 200ms 예산 안 |
| 전체 빌드 | RabbitMQ 통합 테스트 14건·설정 가드 2건·발신함 테스트 4건 추가(리뷰 대응 포함), 전체 310건 통과 |

한계: 퍼블리셔는 채널 하나에서 발행을 직렬화한다(이 규모에서는 충분, 처리량이 문제 되면 채널 풀). 릴레이의 주기 실행과 Spring 배선, 반응 큐(RabbitMQ 버전), 복구 CLI의 Postgres 구현은 M22. 재시도 시각은 호출자 JVM 시계로 계산되고 도래 판정은 DB 시계라 몇 ms 어긋날 수 있다(백오프가 5초 이상이라 실질 영향 없음).

### 19.1 리뷰 대응 (codex 사용량 한도 → code-reviewer 대체: REVISE MAJOR 3·MINOR 7)

- **MAJOR-1(확인 실패 경로 미검증)**: 오류를 유도해 확인한다. 어느 큐에도 묶이지 않은 라우팅 키 → `Failed("unroutable")`, 용량 1 + `reject-publish` classic 큐로 브로커 nack → `Failed("nack")`, 컨테이너 일시 정지(`docker pause`)로 확인이 늦으면 `Unconfirmed("confirm_timeout")`. 변이 검사: 확인 결과를 무시하게(`|| true`) 바꾸면 nack 테스트가, `mandatory`를 `false`로 바꾸면 미라우팅 테스트가 실패하고 원복하면 통과. 정상 중복·강제 종료 테스트에 데드레터 큐가 비어 있음을 단언 추가.
- **MAJOR-2(확인 시간 초과 뒤 같은 채널 재사용)**: 늦게 온 ack·nack·return이 다음 발행 결과에 섞인다(재현 시 정상 저장이 `Failed("nack")`로, 늦은 미라우팅 return이 다음 발행을 `unroutable`로 보고). 시간 초과·확인 중 예외·인터럽트에서 채널을 `abort`하고 버려 다음 발행이 새 채널로 시작한다. 테스트: 일시 정지로 `Unconfirmed`를 만든 뒤 풀면 다음 발행이 `Enqueued`.
- **MAJOR-3(자동 복구 중인 연결을 새 연결로 교체)**: 복구 중 `isOpen()`이 false인 연결을 버리고 새로 만들면 옛 연결이 누수되고, 연결 시도가 락을 쥔 채 최대 5초 걸려 HTTP 스레드가 줄을 서 수신 p95를 깬다. 연결은 처음 한 번만 만들고 이후는 클라이언트 자동 복구에 맡긴다. 열려 있지 않으면 즉시 실패하고(`broker_unavailable`) 헬스 체크도 새 연결을 만들지 않는다. 테스트: 닫힌 연결에서 발행이 1초 안에 `Failed`, 브로커가 연결을 끊으면(`rabbitmqctl close_all_connections`) 자동 복구 뒤 다시 발행.
- **MINOR**: 확인 대기 중 연결 종료는 `Failed`가 아니라 `Unconfirmed`(규칙 4; 요청이 나간 뒤라 저장 여부를 모름). 지연 발행도 mandatory로 보내 미라우팅이면 원본을 ack하지 않음. 이벤트 큐 데드레터링을 `at-least-once`(+`reject-publish`)로. `queue.backend=rabbitmq`는 `queue.rabbitmq-preview=true` 없이는 기동을 거부(Redis 상태 저장소가 delivery tag를 스트림 ID로 오해하고 재시도가 재투입되지 않는 반쯤 배선된 앱 방지, 테스트). 릴레이 한 사이클의 예외가 주기 실행을 멈추지 않게 격리, 깨진 재시도 입력은 폴링을 막지 않고 DLQ로 격리(테스트). 옛 tag ack 주석 정정(자동 복구 채널은 복구 전 tag의 ack를 조용히 무시), 채널 폐기·토폴로지 선언 실패 시 연결 비움.
- **알려진 한계(수정하지 않음)**: ① 지연 큐(`.defer`)의 TTL 데드레터링은 classic 큐라 브로커 장애 순간에 at-most-once다(quorum 큐 + at-least-once로 바꾸는 방안은 검토만 함). ② 릴레이 두 개가 같은 행을 동시에 재발행할 수 있고(`FOR UPDATE SKIP LOCKED`·선점 갱신 없음), 워커 백로그가 `republishAfterMs`보다 길면 같은 세대가 반복 재투입될 수 있다 — 선점 결과표가 흡수해 정확성은 유지되고 큐만 부푼다. ③ 퍼블리셔는 수신·지연 발행·릴레이가 한 채널·한 모니터를 공유한다(ALL 역할에서 지연 발행과 릴레이가 수신 지연을 최대 1초씩 늘릴 수 있음). 필요하면 수신용과 워커용 채널을 분리한다.

## 20. 2단계 후속 M22-1 Postgres·RabbitMQ 전체 배선 검증 (2026-10-02)

M22를 둘로 나눴다. 이번(M22-1)은 새 구성(`queue.backend=rabbitmq` + `state.backend=postgres`)을 끝까지 배선하고 검증하는 일이고, Redis 제거·compose·스크립트·P·D·R 재실험은 M22-2다. 기본값은 아직 Redis라 기존 동작은 그대로다.

추가한 것
- `PostgresRecoveryStore`(복구 CLI의 Postgres 구현: 목록·조회·`resolve-completed`·`close`·`reprocess`). `reprocess`는 재시도 예약과 같은 경로다: 상태를 `RETRY_WAIT(gen+1, manual_gen)`로 올리고 보존 입력을 즉시 도래하는 재시도로 옮기면 릴레이가 큐에 다시 넣는다. 승인은 그 세대의 첫 선점에서 소비된다(B11).
- `UnknownResolver`(코어): 복구 목록의 `UNKNOWN`을 읽기 전용으로 스레드 조회해, 우리 메타데이터 답글이 있으면 완료 처리한다. 없거나 조회가 실패하거나 방금 불명이 된 건은 건드리지 않는다. **자동 재발신은 하지 않는다**(규칙 11).
- `PostgresConfig`·`PostgresMaintenance`: 워커·복구 역할만 DB에 연결(수신은 큐 저장 확인만 하므로 DB가 필요 없음), 재시도 릴레이(5초)·결과 불명 자동 조회(30초)·만료 건 삭제(10분)를 주기 실행. 한쪽 백엔드만 바꾼 반쯤 배선된 앱은 기동을 거부한다(양방향 가드).
- RabbitMQ 반응 큐: 최초 발행이 확인되면 본문 없는 항목(`event_id`·`channel`·`ts`·`received_at`)을 반응 큐에 넣고(최선 노력 — 실패해도 수락을 막지 않음), `RabbitReactionConsumer`가 이모지를 붙인다. 재투입은 반응을 다시 만들지 않는다. Redis 때의 "처리·반응을 한 스크립트로 원자 기록"은 두 번의 발행으로 바뀌었다.

| 검증 | 결과 |
|---|---|
| Postgres 복구 저장소(Testcontainers) | 14건: 목록 정렬·본문 없음, 재시도 예약은 미해결이 아님, 스레드 위치, resolve 멱등·충돌·거절, close 뒤 재전송 무시, reprocess 승인·원래 수신 시각 보존·재실행 멱등·첫 선점 소비·소실 시 DEAD와 다음 세대 승인·24시간 창 초과 건의 수동 실행·보존본 없음 |
| 결과 불명 자동 조회 | 6건: 답글 발견 시 그 ts로 완료, 미발견·조회 실패·방금 불명·DLQ·UNKNOWN 아님은 변경 없음, 재발신·재처리·닫기 호출 0, 사이클 예외 격리 |
| 반응 큐(RabbitMQ) | 4건: 최초 발행만 반응 항목 생성(본문 없음), 재투입은 만들지 않음, 이모지 부착 후 삭제, 실패해도 재시도 없이 삭제 |
| **전체 배선 종단 테스트** | 실제 스프링 컨텍스트(역할 all) + RabbitMQ·Postgres 컨테이너, **Redis 없음**: `/health`가 `rabbitmq`만 보고, 서명된 이벤트가 수신→큐→워커→echo LLM→(가짜 Slack) 답글 1회·반응 1회→`COMPLETED`, 같은 event_id 재전송은 200이고 답글이 늘지 않음, 서명 오류는 401·봇 메시지는 큐에 넣지 않음 |

한계: Slack 호출만 가짜이고 실제 Slack·Ollama와의 왕복은 M22-2의 P·D·R 재실험에서 한다.


### 20.1 M22-1 리뷰 반영과 알려진 한계

리뷰(APPROVE, MINOR 위주)를 반영했다. 반응 발행 대기를 수락 타임아웃 이내로 제한하고 `reaction_enqueue_ms`를 남긴다. `.reactions` 큐에 TTL 60초를 둔다. `UnknownResolver`는 건별 예외를 격리하고 사이클당 10건으로 제한한다. 자동 조회로 완료한 건은 단계 `auto_resolved`로 사람의 `manual_resolved`와 구분한다. 깨진 재시도 payload는 `preserved_input`을 dlq로 격리하고 상태도 `DEAD`로 바꿔 복구 CLI로 닫을 수 있다. 마이그레이션 실패 시 DataSource를 닫는다. 검증: `RoleWiringIT`(역할별 빈 구성 4), 복구·해결기·반응 테스트 추가, 전체 빌드 통과.

알려진 한계(M22-2 이후 재검토):
- Slack 속도 제한은 반응과 답글이 같은 토큰을 공유해 서로 영향을 준다.
- 반응 발행 확인 왕복이 수락 경로에 들어 있다(상한: 수락 타임아웃).
- `republishAfterMs`(120초)는 적체 시 재발행을 늘릴 수 있다. 중복은 선점 표가 흡수한다.
- 자동 조회와 재처리 사이에 좀비 전송이 끼어들 수 있다(결과 불명 정책상 자동 재발신은 하지 않는다).
- `resolve`가 이상 상태로 보존된 행을 함께 지운다.

## 21. 2단계 후속 M22-2 Redis 제거와 P·D·R 재실험 (2026-10-02)

**구분: 검증 수행 완료 = 예(P·D·R). 합격 판정은 §21.3의 한계 안에서.**

### 21.1 변경과 환경

Redis 어댑터·Lua·의존성·백엔드 스위치를 제거했다(큐=RabbitMQ, 상태=Postgres 고정). 적체 스냅샷은 `BacklogProbe` 포트로 재구성(`retry dlq recovery queue_ready defer dead`), `scripts/p1-residue-check`를 Postgres·RabbitMQ 기준으로 다시 썼다. 환경은 §15.1과 같은 장비(M1 8코어, RAM 16GB)·`qwen2.5:7b`·워커 1개·LLM 동시성 1이고, 컨테이너만 `rabbitmq:3.13-alpine`(512MB)·`postgres:16-alpine`(384MB)·앱 3개(각 512MB)로 바뀌었다. 요청은 §15와 같은 합성 서명 이벤트(실제 테스트 채널). 컨테이너는 포트 18080(호스트 8080을 옛 bootRun이 점유). 빌드: 단위·통합 258건 통과.

### 21.2 결과

| | n | 수신 `recv_ms` p95 | 답변 `answer_ms` p95 | 유실·중복 |
|---|---|---|---|---|
| (a) 순차 20건 | 20 | **71ms**(p50 42, max 82) | 6419ms(p50 2532), 표본 18 | 0·0 |
| (b) 버스트 5×2 | 10 | **129ms**(p50 78) | 17458ms(적체 관측, 판정 제외) | 0·0 |

`enqueue_ms` p95 18~19ms(발행 확인 포함), `reaction_ms` p95 494ms(순차)·1020ms(버스트). 발행·반응 실패 0, 음수 구간 0. 적체 스냅샷은 정상 출력(`queue_ready=0 defer=0 dead=0`). 각 실험 뒤 `p1-residue-check` → `OK: 잔존물 0`.

| 실험 | 결과 |
|---|---|
| D1 동일 event_id 10회 동시 / D2 완료 뒤 재전달 | 최종 답글 **1개** / 추가 **0개** |
| D3 서로 다른 event_id 10건 | 정확히 **10개**, 유실·중복·실패 안내 0 |
| R1 큐 저장 직후 수신 `kill -9` | 워커가 처리, 답글 1개 |
| R2 처리 중 워커 `kill -9`(워커 2, 느린 모드 30초) | 남은 워커가 임대 만료를 기다려 인수(`defer`로 재시도), **64초** 만에 새 `attempt_id`로 완료, 답글 1개(Redis 때 153초) |
| R3 발신 직후 halt(137) → 정상 재기동 | `UNKNOWN(sending_lease_expired)` → `UnknownResolver`가 우리 metadata 답글을 읽기 전용으로 찾아 `COMPLETED/auto_resolved`. 재발신 0, 답글 1개, 잔존물 0 |

### 21.3 관측과 한계

- **언어 방어가 실제로 작동했다.** 순차 20건 중 1건(질문 2번)이 재시도 3회 모두 한자·가나 혼용으로 `llm_failed:language_violation` → 최종 실패 안내로 끝났고(버스트 1건도 같음), 사용자에게 한자가 노출된 건은 0이었다. 방어의 비용은 `재시도 4회 + 지연` — 정상 답변률(event_id별 마지막 결과)은 순차 95%·버스트 90%. 모델이 특정 질문에서 일관되게 위반한다는 뜻이므로 3단계 전에 프롬프트·모델을 점검할 후보다(미해결).
- 재시도 경로(Postgres 발신함 → `RetryRelay` → RabbitMQ 재투입)가 실제 부하에서 gen 1→2→3으로 동작했다.
- 한계: 합성 요청(Slack→ngrok 구간 미포함), 표본이 작고 각 실험 1회, 같은 장비에서 Ollama와 경합. 호스트 8080의 옛 bootRun은 건드리지 않았다.
- 판정: B16(수신 p95 ≤ 200ms, 순차 답변 p95 ≤ 45s) ✓, B3·B4·B5·B6·B7·B15·B18 ✓(R1·R2·R3·D1~D3·residue).

### 21.4 리뷰 대응 (code-reviewer: REVISE MAJOR 2·MINOR 7)

`p1-residue-check`가 명령 치환 안의 `exit 3`이 서브셸만 끝내 질의 실패에도 `OK`를 낼 수 있었다 → 질의를 최상위 변수에 담아 즉시 검사 불가(3)로 끝내고 숫자 응답을 검증한다(실패 유도 확인: 컨테이너가 없을 때 3). `p1-metrics`가 프로브 순서에 따라 적체 줄을 놓칠 수 있었다 → `queue_ready=` 포함 여부로 매칭. 그 밖에 `BacklogReporter` 프로브별 예외 격리, `RoleWiringIT` 보강(리포터·프로브는 워커만, 수신·복구에 LLM 없음), `RabbitPipelineIT`의 재시도 루프가 `Unconfirmed`를 덮지 않도록 단언 추가, compose 비밀번호 주의 문구, ARCHITECTURE 상단에 Redis 서술이 역사 기록임을 명시.

## 22. 2단계 후속 M23 알람 입력 어댑터 검증 (2026-10-02)

**구분: 외부 왕복 = 실제 Slack 채널에 알람 리포트 1건(echo LLM, 로컬 RabbitMQ·Postgres 컨테이너). 실제 CloudWatch/SNS 연동은 하지 않았다(AWS 환경 없음) — SNS 봉투 형식의 합성 요청으로 검증했다.**

- 구현: `AlertController`(`/alerts/{source}`, 시크릿), `CloudWatchAlertNormalizer`, `AlertEvent.toMessageEvent()`. 알람은 `ts` 없는 메시지 이벤트로 기존 파이프라인을 탄다(리포트는 채널의 새 메시지, 반응 없음).
- 단위·통합: 정규화 5, 컨트롤러 6(401·404·200·503·400·구독 확인), `FullStackWiringIT` 알람 2(새 리포트 1건 → 같은 회차 재전송은 리포트 불변 → 다른 상태 변경 시각은 새 리포트, 틀린 시크릿은 아무것도 발신 안 됨), `PostgresRecoveryStoreTest`(스레드 위치 없는 건은 자동 조회 제외).
- 왕복: 정상 알람 200 → 실제 Slack 발신 성공 1건(`COMPLETED/delivered`), 같은 알람 재전송 200이지만 발신은 여전히 1건. 오류 유도: 틀린 토큰 401, 깨진 본문 400, RabbitMQ 중지 상태 503.
- 한계: SNS 서명 미검증(시크릿 인증, HTTPS 전제), 알람 리포트의 결과 불명은 자동 조회 대상이 아님, 알람별 채널 라우팅 없음(전역 `alert.channel`), Grafana 어댑터 없음(포트만). 실제 LLM(qwen) 품질·프롬프트는 이번 범위가 아니다.

### 22.1 리뷰 대응 (code-reviewer: REVISE MAJOR 1·MINOR 10)

MAJOR: SNS 구독 확인 URL이 로그에 없어 구독을 확인할 수 없었다 → https의 `sns.<region>.amazonaws.com`일 때만 `SubscribeURL`을 로그에 남긴다(서버는 열지 않음). 반영한 MINOR: 빈 `X-Alert-Secret` 헤더가 토큰을 가리지 않게, 알람 본문 2000자·제목 200자 상한, 알람 데이터를 `<alarm>` 블록으로 감싸 지시와 분리, 알람 리포트 전용 실패 안내("다시 멘션" 문구 제거), 중복 키에 계정·리전 포함, `Unconfirmed → 503` 테스트. 남긴 항목: 요청 본문 크기 제한(인증 전 읽기)은 프록시/서블릿 한도에 맡김, `?token=`이 ngrok 인스펙터·접근 로그에 남는 점(헤더 시크릿 권장), `<!channel>` 방송 문자열 제거, 알람 스레드에서 실제 후속 멘션은 미검증.
