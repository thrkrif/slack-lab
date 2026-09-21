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
