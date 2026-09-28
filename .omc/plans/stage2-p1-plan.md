# 2단계(P1) 작업 계획 — slack-lab

**상태: pending approval** (ralplan consensus 5회전 완료 — Architect APPROVE, Critic(codex) 마지막 지적 1건은 Planner가 반영(재검토 없음). 착수는 1단계 완료와 2단계 Git 정책 승인 이후)
근거: [`PRD.md`](PRD.md) §4 P1-1~P1-8·§5·§7, [`ARCHITECTURE.md`](ARCHITECTURE.md) §2·§3.1·§3.2·§4.1·§5.1·§9·§11, [`AGENTS.md`](../../AGENTS.md) 규칙 1·2·5·11·12
범위: **P1-1 ~ P1-8만.** RAG·임베딩·pgvector·LangGraph·n8n·모델 라우팅·지표 스택은 넣지 않는다(규칙 1).

## 진행 상태 (2단계)

- [ ] **착수 조건 (사용자 요청 시에만)** — (1) 1단계 완료: `develop`→`main` merge commit, 태그 `v0.1.0`, `AGENTS.md` 현재 단계 줄 "2단계" (2) **2단계 Git 정책 확정**: AGENTS.md의 자동 병합 승인은 "1단계 한정"이므로, 2단계에서도 작업 단위마다 브랜치→PR→`develop` 자동 병합을 할지 사용자가 정해 AGENTS.md에 기록한다
- [ ] **M9 착수 결정 + 타당성 스파이크** — 큐·워커 ADR, Redis 크래시 내구성·지연, 기본 Ollama 설정에서 10 동시 p95, Slack 429 방침
- [ ] **M10 인프라·설정 골격** — Redis 구동 설정, 의존성, `app.role`, 설정 record·불변식 검증
- [ ] **M11 공유 처리 상태 저장소** — `ProcessingStateStore`(Redis Lua CAS·임대·gen), 선점 결과표, 원자 종료 스크립트, 통합 테스트 (기존 P0 타입·호출부는 그대로)
- [ ] **M12 수신–큐–워커 분리** (P1-1·P1-2) — 발행·소비·상태 확정 후 ACK, 핸들러 계약 변경, `EventDeduplicator` 제거
- [ ] **M13 재시도·DLQ** (P1-4) — 오류 전이표, 지연 재시도(ZSET), 최종 안내, DLQ, 24시간 창
- [ ] **M14 결과 불명 복구** (P1-3) — **착수 전 Slack 스코프 재설치에서 멈춤**, `recovery` CLI(수동 재처리 승인 포함), 발신 직후 halt 유도
- [ ] **M15 즉시 반응** (P1-5) — 별도 반응 스트림·소비자가 "확인 중" 이모지, 최초 수신→표시 시간 측정
- [ ] **M16 스레드 문맥** (P1-6) — `conversations.replies`로 이전 대화를 프롬프트에 포함
- [ ] **M17 관측 + 다중 워커** (P1-7·P1-8) — 단계별 소요·적체·실패율 로그 집계, 워커 2프로세스
- [ ] **M18 P1 검증 실험** — 성능 100건·중복·복구, `EXPERIMENT-LOG.md`, PRD §6 2단계 판정
- [ ] **2단계 완료** (`develop`→`main`, 태그 `v0.2.0`, 현재 단계 줄) — **P1 합격(또는 사용자가 승인한 목표 변경 후 합격) + 사용자 요청 시에만**

각 마일스톤을 마칠 때 이 목록, `다음 세션 핸드오프` 소절(덮어쓰기), `EXPERIMENT-LOG.md`를 갱신한다. 구조가 바뀐 마일스톤(M12·M13·M14·M15)은 같은 작업에서 `ARCHITECTURE.md`도 갱신한다(규칙 9).

## 1. 요구사항 요약

수신 서버는 서명 검증·필터링 후 이벤트를 **큐에 저장하고 저장 확인을 받은 뒤에만** 200을 준다(P1-1).
워커가 큐를 소비해 공유 상태 저장소에서 처리 권한을 선점하고, 기존 `SlackEventHandler`로 LLM→답글을 수행한다(P1-2).
상태는 프로세스 밖(Redis)에 두어 재시작·재전달·다중 워커에서도 최종 답글이 1개다(P1-3·P1-8).
일시 실패는 최대 3회 재시도하고, 최종 안내가 실패하면 DLQ로 보낸다. 전송 결과 불명은 자동 재발신 없이 복구 대상으로 남긴다(P1-4, 규칙 11).
"확인 중" 표시(P1-5)와 스레드 문맥(P1-6)을 더하고, 처리 시간·적체·실패율을 로그로 본다(P1-7).
최종 산출물은 PRD §5의 **고정 환경 100건 성능 검증·중복 검증·복구 검증**이다.

## 2. 수락 기준 (테스트 가능 형태)

| # | 기준 | 판정 방법 |
|---|---|---|
| B1 | 유효한 새 이벤트는 `XADD` 후 `WAITAOF`가 로컬 fsync 1을 반환한 뒤에만 200. 저장 실패·확인 시간 초과·반환값 불일치는 503 | Redis 중단·타임아웃 유도 curl → 503. 정상 시 `enqueued` 로그 후 200 |
| B2 | 수신 역할에서는 핸들러·워커·`LlmClient`·`SlackClient` 빈이 생성되지 않고 LLM·Slack 호출이 없다 | 역할별 컨텍스트 테스트 + 로그 |
| B3 | 큐 저장 후 수신 서버를 `kill -9`해도 이벤트가 워커에서 처리된다 | 복구 실험 R1 |
| B4 | 워커가 처리 중일 때 `kill -9`하면, 임대 만료 후 재처리되어 최종 답글이 1개다 | 복구 실험 R2 |
| B5 | Slack 발신 성공 직후·완료 기록 전 중단 → ACK되지 않은 메시지를 `XAUTOCLAIM`이 회수하고, 결과표에 따라 입력 보존 → 만료 `SENDING`→`UNKNOWN` 전이 → ACK가 `finalize` 순서대로 수행됨. 자동 재발신 0회. `recovery check`→`resolve`로 해결 | 복구 실험 R3 (`experiment.halt-after-send`) |
| B6 | 동일 event_id 10회 동시 전달 → 최종 답글 1개. 완료 후 재전달 → 답글 추가 0개 | 중복 실험 D1·D2 |
| B7 | 서로 다른 event_id 10건 → 답글 정확히 10개 | 중복 실험 D3 |
| B8 | 재시도 가능 오류(LLM 연결·5xx·기한 초과, 미전송이 확실한 Slack 일시 실패)는 최초 1회 + 재시도 3회(5s·30s·120s), 소진 시 최종 안내 1회. 영구 오류는 재시도 0회, 결과 불명은 재발신 0회 | §3 M13 전이표의 각 행을 유도하고 로그의 `gen` 확인 |
| B9 | 최종 안내가 명확히 실패하면 `DEAD`+DLQ, 결과가 불명이면 `UNKNOWN`+복구 목록. `finalize`·재시도 예약·재투입은 **멱등 보존/투입 → 상태 → 삭제·ACK** 순서라, 어느 명령 사이에서 오류·중단이 나도 입력이 보존된 채 종료된다(Lua는 롤백하지 않으므로 순서로 보장) | `slack.base-url` 스텁 유도 + 테스트 전용 fault 인자(`fail_after=N`, 운영 빈에서는 비활성)로 각 쓰기 명령 뒤 실패 주입·스크립트 전후 kill 뒤 재전달 통합 테스트 |
| B10 | 상태·재시도 예약·DLQ·복구 기록의 저장이 실패하거나 거절되면 XACK하지 않는다(메시지는 pending에 남음) | 상태 저장소 오류 주입 통합 테스트 |
| B11 | 최초 수신 후 24시간이 지난 미완료 이벤트는 자동 실행되지 않고 복구 대상이 된다(판정은 Redis 시각). 사람이 `reprocess`로 승인하면 **승인한 gen의 첫 선점에서 승인이 소비되어 정확히 1회만** 실행되고, 그 시도가 실패·소실되면 **24시간 이내·초과와 무관하게** 새 승인 전까지 복구 대상으로 돌아간다 | 시각 주입 통합 테스트(자동 차단, 수동 승인 성공, 24시간 이내·초과 각각에서 승인 실행 중 워커 중단 후 새 승인 없는 실행 0회, 승인 실행의 일시 실패) |
| B12 | 멘션 **최초 수신부터** "확인 중" 표시까지 p95 ≤ 3초. LLM 워커 적체·빠른 답변 처리 어느 쪽에서도 누락 0, 표시 실패해도 답변 흐름은 계속 | 실제 멘션 + 워커 정지(적체) + 반응 소비자 지연(답변 먼저 끝남) + `reactions.add` 오류 유도 |
| B13 | 스레드 안에서 되물으면 이전 대화(최대 N개·M자)가 프롬프트에 들어간다. 조회에 실패하면 문맥 없이 진행하고 로그를 남긴다 | 실제 스레드 2턴 대화 + 조회 실패 유도 |
| B14 | 처리 시간(수신·큐 저장·큐 대기·LLM·발신·답변), 큐 적체(`XLEN`·pending·재시도 ZSET·DLQ·복구 목록), 실패율을 로그 grep으로 집계할 수 있다 | `EXPERIMENT-LOG.md` 집계 절차 실행 |
| B15 | 워커 2개(`docker compose --scale worker=2`)를 동시에 돌려도 B6·B7이 성립한다 | 다중 워커 실험 |
| B16 | PRD §5 고정 환경 100건(10 동시 × 10 배치)에서 수신 p95 ≤ 200ms, 정상 답변 p95 ≤ 30s, 유실·실패·중복 0 | 성능 실험 P, `ceil(0.95×N)` |
| B17 | `event/` 패키지에 HTTP 타입 import가 없다(규칙 2, A13 유지) | `grep -rE "jakarta\.servlet|org\.springframework\.http" src/main/java/com/slack/lab/event/` 0건 |
| B18 | 해결된 이벤트(완료·복구 종료)의 입력 본문이 스트림·재시도 ZSET·보존 해시 어디에도 남지 않는다. 상태 해시에는 메타데이터만 있다(PRD §7) | `scripts/p1-residue-check`: 해결된 event_id마다 본문 잔존 0, 미해결 건 수 = DLQ+복구 목록 항목 수 = 보존 해시 수 |
| B19 | `./gradlew build` 통과. **완료 판정은 B1~B18** | 규칙 5 |

## 3. 구현 단계

각 단계는 빌드 통과가 아니라 **외부 왕복 또는 오류 유도 확인**으로 완료한다(규칙 5). 단계마다 `feature/mN-…` 브랜치를 쓴다. PR 병합 방식은 착수 조건 (2)에서 정한 정책을 따른다.

### M9. 착수 결정 + 타당성 스파이크 (코드는 `docs/spikes/`만)
- **결정 기록**(PRD §8 열린 질문 해소): 큐는 Redis Streams, 워커는 Java(같은 코드베이스, 역할 분리)로 한다. `ARCHITECTURE.md` ADR-5 갱신·ADR-8 추가, PRD §8 표 갱신.
- **Redis 스파이크**(`WAITAOF`는 7.2+, `XACKDEL`은 8.2+ 필요 — 현재 8.2.1. `XACKDEL`의 단일 명령 동작도 확인):
  - `appendonly yes` + `appendfsync always`에서 `XADD` 후 raw 명령 `WAITAOF 1 0 <ms>`의 반환값(numlocal=1)과 지연 p95를 잰다. Spring Data Redis에는 이 API가 없으므로 raw 명령 실행 경로도 함께 확인한다.
  - Redis는 `redis:8.2-alpine` 컨테이너(AOF always)로 띄운다. `docker kill -s KILL` 후 재기동해 메시지 보존을 확인한다. **주장 범위는 "프로세스 크래시 내구성"으로 한정한다.** OS 크래시·전원 차단은 로컬에서 재현할 수 없어 미검증으로 기록한다(`kill -9`만으로는 `always`와 `everysec`를 구별하지 못한다).
- **Ollama 타당성**(PRD §5 환경 고정 준수):
  - P0 기록 환경(Ollama 기본 설정, `OLLAMA_NUM_PARALLEL` 미설정)과 `llm.max-tokens`를 그대로 두고, 고정 질문 세트로 10건 동시 호출 3배치를 잰다. `ollama ps`와 로그로 실제 추론 동시성을 기록한다.
  - 이 환경에서 **워커 동시성**을 정해 이후 모든 배치에 고정한다. 워커 수는 PRD가 P1에서 정하도록 허용한 값이다.
  - Ollama 설정을 바꾼 탐색 측정은 참고용으로만 별도 표에 적고, 합격 판정에는 쓰지 않는다.
- **Slack 429 방침**: 한 채널에 10건을 연속 발신해 429 발생 여부를 관측한다. 발생하면 테스트 채널을 여러 개로 나눈다(봇 초대가 필요하므로 **사용자 조작 멈춤**). 판정 규칙도 여기서 정한다: 429 뒤 `Retry-After`를 지켜 발신에 성공하면 정상 답변으로 세되, 대기 시간은 답변 시간에서 빼지 않는다.
- **판정**: 기본 환경에서 답변 p95 30초에 도달할 수 없다는 실측이 나오면 **멈추고 사용자에게** 목표 변경안(근거·변경값)을 제시한다. PRD 목표·환경 변경은 사용자가 결정한다.
- **완료**: `EXPERIMENT-LOG.md` 환경 고정표에 워커 동시성·실측 추론 동시성·max-tokens·Redis 버전·AOF 설정·테스트 채널 수 추가, 스파이크 결과와 ADR 반영.

### M10. 인프라·설정 골격
- **Docker Compose 구성**(2026-09-28 사용자 결정): 루트 `compose.yaml`에 다음을 둔다. 실행 명령은 `AGENTS.md` 명령어 절에 추가한다.
  - `redis`: 이미지 `redis:8.2-alpine`(`XACKDEL`에 8.2+ 필요). `infra/redis.conf`(AOF always)를 마운트하고, 데이터는 named volume에 둔다. 포트는 6379.
  - `receiver`·`worker`·`reactor`: 루트 `Dockerfile`(멀티 스테이지 — Gradle `bootJar` → `eclipse-temurin:21-jre`)로 빌드한다. `APP_ROLE`로 역할을 고르고 `env_file: .env`를 쓴다. compose profile `app`으로 묶는다. 호스트에는 `receiver`의 8080만 노출한다(ngrok은 호스트에서 `localhost:8080`을 가리킨다).
  - **Ollama는 컨테이너로 띄우지 않는다.** 로컬에 이미지가 없고 모델 용량이 크기 때문이다. 호스트의 `ollama serve`를 `LLM_BASE_URL=http://host.docker.internal:11434/v1`로 호출한다. P0 환경 고정(PRD §5)도 그대로 유지된다.
  - 개발 루프: `docker compose up -d redis` + 호스트 `bootRun`. 통합 검증과 M17·M18 실험은 `docker compose --profile app up`으로 고정한다. 테스트는 Testcontainers를 쓴다.
- 의존성: `spring-boot-starter-data-redis`(Lettuce), 테스트용 `org.testcontainers:junit-jupiter` + `redis:8`.
- `app.role=receiver|worker|reactor|recovery|all`. 수신 역할만 웹 서버를 띄운다(ngrok에 노출되는 포트는 하나). 핸들러·워커·LLM·Slack 발신 빈은 역할 조건으로 등록한다(B2). 1단계 흐름은 M12 전까지 `all`로 유지한다.
- 설정 record 네 종류:
  - `queue.*`: stream·그룹 키, `enqueue-timeout-ms`(M9 결과), `claim-min-idle-ms=100000`(총 기한 60초 + 임대 30초 + 여유 10초)
  - `state.*`: `lease-ms=30000`, `renew-ms=10000`, `completed-retention-days=7`, `execution-window-hours=24`
  - `retry.*`: `backoff-ms=5000,30000,120000`, `max-retries=3`
  - `worker.concurrency`: M9에서 정한 값
- **1단계 후속 과제 흡수**: 기동 시 설정 불변식을 검사하고, 위반하면 기동을 실패시킨다.
  - `llm.deadline-ms + slack.send-deadline-ms ≤ processing.total-deadline-ms`
  - `renew-ms ≤ lease-ms / 3` (갱신 1회 실패로 임대를 잃지 않게)
  - `claim-min-idle-ms ≥ processing.total-deadline-ms + lease-ms`
- 수신 역할의 `/health`에 Redis ping 결과를 넣는다.
- **완료**: `docker compose up -d redis` 후 역할별 bootRun에 성공하고, `docker compose --profile app up`으로 수신·워커 컨테이너가 떠서 컨테이너 안에서 호스트 Ollama 호출(`/v1/models`)에 성공한다. 수신 역할의 빈 구성 테스트(B2)가 통과한다. **기본값으로 기동 성공**, 각 불변식의 경계 바로 밖 값으로 기동 실패를 테스트로 확인한다.

### M11. 공유 처리 상태 저장소 (P1-3·P1-8 기반)
- `ProcessingStateStore` 인터페이스 + `RedisProcessingStateStore`. 이 마일스톤에서는 **새 패키지 `state/`에 P1 전용 타입(상태 enum·선점 결과·시도 핸들)을 따로 두고 도입·테스트만** 한다. 기존 `AttemptHandle`·`ProcessingState`·`EventDeduplicator`·`SlackEventHandler`는 건드리지 않는다. 계약 변경과 호출부 전환은 M12에서 함께 한다.
- 키 `slack:evt:{event_id}` 해시의 필드: `state`, `attempt_id`, `gen`, `lease_until`, `first_received_at`(최소값 유지), `retries`(자동 재시도 횟수 — gen과 별개), `retry_at`, `manual_gen`(승인된 세대, 선점 시 소비), `manual_run`(현재 gen이 수동 승인 실행임을 영속 표시), `stage`, `kind`, `slack_ts`, `channel`, `thread_ts`. 본문은 넣지 않는다.
- **보존 저장소**: 복구·DLQ 입력은 스트림이 아니라 `slack:preserved:{event_id}` 해시(본문·`received_at`·gen)에 `HSET`으로 두고, 목록은 ZSET `slack:dlq`·`slack:recovery`(member=event_id)에 `ZADD`한다. 둘 다 같은 값을 다시 써도 결과가 같아(멱등) 부분 실패 후 재실행이 안전하다.
- 상태: `PROCESSING`·`SENDING`·`RETRY_WAIT`·`COMPLETED`·`UNKNOWN`·`DEAD`·`CLOSED`(`recovery close`로 사람이 종료, `COMPLETED`와 같은 7일 보존). P0의 `FAILED`는 둘로 나눈다. 재시도 예약이 있으면 `RETRY_WAIT`, 끝났으면 `DEAD`다.
- **gen 불변식**: 상태의 gen을 먼저 올린 뒤에만 그 gen의 메시지를 투입한다(재시도 스케줄러·`reprocess` 모두 Lua 한 번). 따라서 m > s는 정상 경로에서 생기지 않는다. gen은 세대 식별용이고 시도 횟수는 `retries`로 센다.
- 모든 전이는 **Lua 스크립트 CAS**로 한다. 소유자·상태·gen·임대를 한 번에 확인하고, 시각은 Lua 안의 `TIME`(Redis 시계)으로 잰다. `get` 후 `set`은 쓰지 않는다.
- **선점 결과표**(메시지 gen = m, 상태 gen = s). **위에서부터 처음 맞는 행 하나만 적용한다.**

| 순위 | 현재 상태·조건 | 결과 | 큐 처리(모두 한 Lua) |
|---|---|---|---|
| 1 | 상태가 있고 m > s (불변식 위반). 상태가 없으면 이 행을 건너뛴다 | `Anomaly` | ERROR 로그, 입력을 보존·DLQ 목록에 추가 후 XACKDEL. 상태는 바꾸지 않음 |
| 2 | `COMPLETED`/`CLOSED` | `Done` | XACKDEL |
| 2' | `UNKNOWN`/`DEAD`, m == s | `Done` | 보존 해시가 없으면 먼저 보존(멱등) → XACKDEL. 부분 실패한 `finalize`의 복구 경로. 사람이 해결한 건은 이미 `COMPLETED`/`CLOSED`이므로 되살아나지 않는다 |
| 2'' | `UNKNOWN`/`DEAD`, m < s | `Done` | XACKDEL(재보존 없음) |
| 3 | m < s | `Stale` | XACKDEL |
| 4 | `SENDING`, 임대 만료 | `UNKNOWN`으로 전이(재선점·24시간 판정보다 우선) | `finalize` 순서: 보존·복구 목록 → 상태 → XACKDEL |
| 4' | `PROCESSING`, 임대 만료, `manual_run` | `DEAD(manual_attempt_lost)`(24시간 판정과 무관) | `finalize` 순서: 보존·DLQ 목록 → 상태 → XACKDEL. 새 승인 없이는 재실행하지 않는다 |
| 5 | `PROCESSING`/`SENDING`, 임대 유효 | `Busy` | ACK 안 함(pending 유지, XAUTOCLAIM이 재확인) |
| 6 | `RETRY_WAIT`, `retry_at` 미도래 | `Stale` | XACKDEL (예약된 재투입이 따로 있음) |
| 7 | 실행 가능(없음 / 임대 만료 `PROCESSING` / 도래한 `RETRY_WAIT`) + `first_received_at`에서 24시간 초과 + (`manual_gen` ≠ m) | `Expired` → `DEAD(window_expired)` | `finalize` 순서: 보존·DLQ 목록 → 상태 → XACKDEL |
| 8 | 실행 가능(위와 같음) | 새 `attempt_id`로 `PROCESSING`(gen=s, 없으면 m). `manual_gen` == m이면 **같은 스크립트에서 `manual_gen`을 지워 승인을 소비**하고 `manual_run=true`를 기록한다(아니면 `manual_run=false`) | 처리 |

- **종료 스크립트** `finalize(event_id, attempt_id, to_state, stream_id, dest)`. Redis Lua는 실행이 원자적이지만 **중간 오류를 롤백하지 않는다.** 그래서 롤백 대신 순서와 멱등성으로 보장한다.
  1. 검증만 한다(쓰기 없음): 소유자·상태 CAS 조건, `XRANGE` 결과가 비어 있지 않음, 대상 키 타입. 하나라도 어긋나면 아무것도 쓰지 않고 거절한다.
  2. 보존: (dest가 있으면) `slack:preserved:{event_id}` `HSET` + 목록 `ZADD`. 멱등이다.
  3. 상태 전이 `HSET`.
  4. `XACKDEL`(Redis 8.2+: ACK와 본문 삭제를 **단일 명령**으로 수행 — `XACK` 뒤 `XDEL` 전 실패로 본문이 pending 밖에 남는 틈이 없다).
  - 2 뒤에 실패하면 상태는 그대로이고 메시지는 pending에 남아, 재전달 때 다시 실행된다. 3 뒤에 실패하면 결과표 2'행이 보존을 확인하고 ACK한다. 어느 경우에도 입력이 사라지지 않는다.
  - 재시도 예약(M13)과 `reprocess`(M14)도 같은 원칙(검증 → 멱등 쓰기 → 상태 → ACK)을 따른다.
- P1 시도 핸들: 모든 전이는 저장 성공 여부를 반환한다. 완료는 `slackTs`·`kind`를 함께 기록한다. **같은 `attempt_id`에 한해 `UNKNOWN→COMPLETED` 전이를 허용**하고, 이때 보존 해시와 복구 목록 항목도 같은 스크립트에서 지운다(B18). 임대 만료 뒤 늦게 도착한 성공 보고가 거짓 UNKNOWN으로 남지 않게 하기 위해서다.
- 임대 갱신기: 소유자만 갱신할 수 있다. 갱신에 실패하면 이후 `markSending()`이 false를 반환한다.
- 테스트(Testcontainers): 결과표의 각 행과 **행 간 우선순위 조합**(만료 `SENDING`+24시간 초과 → `UNKNOWN`, `COMPLETED`+24시간 초과 → `Done`, m<s+24시간 초과 → `Stale`, 수동 승인+24시간 초과 → 실행), 동시 N 선점 1승, 소유자가 아닌 전이 거절, 늦은 `UNKNOWN→COMPLETED`와 복구 항목 삭제, 갱신 실패 후 발신 거절, `finalize` 검증 실패 시 무적용, **테스트 전용 `fail_after=N`으로 각 쓰기 명령 뒤 실패 주입 → 재전달 시 입력 보존·정상 종료**, 해결된 건에 늦은 재전송이 와도 재보존 0, 상태 해시 없음 + gen>0 메시지, TTL(`COMPLETED` 7일, `UNKNOWN`·`DEAD`는 없음).
- **완료**: 통합 테스트 통과. `redis-cli`로 해시·TTL을 직접 확인한 기록을 남긴다.

### M12. 수신–큐–워커 분리 (P1-1·P1-2)
- **측정 경계**: 최상위 서블릿 필터가 진입 시각(`received_at`, UTC epoch ms)을 잡고, 응답 커밋 뒤에 `recv_ms`를 기록한다. 컨트롤러 내부 시간과 구분한다. `received_at`은 큐 메시지에 불변으로 실어 재시도·재투입 때도 보존한다.
- `EventPublisher`: `XADD slack:events`에 `schema_version`·`event_id`·`gen=0`·`received_at`·`channel`·`ts`·`thread_ts`·`user`·`text`·`retry_num`을 담는다. 이어서 `WAITAOF 1 0 queue.enqueue-timeout-ms`를 보내고 numlocal=1인지 검사한다. `WAITAOF`는 차단 명령이라 Lua 안에서 쓸 수 없으므로, 발행 스크립트가 끝난 뒤 별도 명령으로 한 번 호출한다. 결과는 `Enqueued`/`Failed`/`Unconfirmed` 중 하나다. `enqueue_ms`와 큐 저장 시각을 로그에 남긴다.
- `SlackEventController`: 서명 검증과 무시 필터를 거친 뒤 발행 결과를 HTTP로 바꾼다(`Enqueued`→200, 나머지→503). 중복 입력은 거르지 않는다(ARCHITECTURE §3.1). `EventDeduplicator`와 `experiment.dedup-enabled`는 이 마일스톤에서 제거한다. 1단계 실험은 `v0.1.0`에서 재현할 수 있다.
- **핸들러–워커 계약**(규칙 2가 허용하는 결과 타입·호출부 변경). 기존 P0 `AttemptHandle`·`ProcessingState`를 M11의 P1 타입으로 이 마일스톤에서 교체한다:
  - 핸들러가 맡는 것: LLM 호출, `markSending()` 게이트, 발신. 결과로 `Delivered(kind, slackTs)`, `Failed(stage, retryable)`, `Unknown(stage)`, `Rejected`를 돌려준다. 종료 상태 기록(`markCompleted`/`markFailed`/`markUnknown`)은 하지 않는다.
  - 워커가 맡는 것: 결과에 따라 종료 상태를 기록하고 **반환값이 true일 때만** XACKDEL한다. 기록이 거절되거나 실패하면 ACK하지 않는다(B10). 예외 가드도 워커로 옮긴다. `markSending` 이전의 예외는 `Failed`, 이후의 예외는 `Unknown`으로 처리한다.
- `EventWorker`: `worker.concurrency`개 스레드가 `XREADGROUP`으로 소비한다. M11의 선점 결과표를 따르고, 주기적으로 `XAUTOCLAIM(min-idle=claim-min-idle-ms)`을 돌려 죽은 워커의 메시지를 회수한다. `t0`는 워커가 선점한 시각이며, 한 번의 시도에 60초 예산을 그대로 적용한다(ARCHITECTURE §4.1).
- **M12 시점의 결과 처리**(M13 이전 최소 정책 — 결과 불명은 자동 재발신하지 않음):
  - `Delivered` → `COMPLETED` 저장 확인 후 XACKDEL.
  - `Unknown` → `finalize(UNKNOWN, dest=recovery)`: 보존 → 전이 → XACKDEL.
  - `Rejected` → ACK하지 않는다. 소유권을 재확인하는 대상이며, XAUTOCLAIM이 가져가면 결과표대로 판정한다.
  - `Failed` → `finalize(DEAD, dest=dlq)`. 재시도는 M13에서 이 경로를 대체한다. M12~M13 사이 DLQ는 `redis-cli ZRANGE slack:dlq 0 -1`과 보존 해시로 확인한다(`recovery list`는 M14). P0에서는 재전송 때 다시 선점할 수 있었으므로 일시적인 퇴행이며, M13에서 해소된다.
- `ARCHITECTURE.md` §1·§2·§5·§9를 실제 구조로 갱신한다. RAG 자리에는 `// 3단계에서 여기가 바뀐다` 주석만 남긴다.
- **완료**:
  - 실제 멘션 → 수신 200(큐 경유) → 워커 → 스레드 답글까지 왕복한다.
  - B1(503 유도)·B2·B3·B4·B10을 확인한다.
  - `Unknown`(끊김 스텁)과 `Rejected`(임대 강제 만료) 유도에서 재발신이 0회인지 확인한다. `finalize` 직전·직후 kill과 명령 사이 오류 주입에서 입력이 유실되지 않는지(보존 해시 또는 pending 중 한 곳에 존재) 확인한다. 이 확인들이 병합 조건이다.

### M13. 재시도·DLQ (P1-4)
- **오류 분류는 클라이언트 경계에서 한다.**
  - `LlmResult`: 재시도 가능(연결 실패·5xx·기한 초과) / 영구(4xx·모델 없음·파싱 실패).
  - `SlackSendResult`: 미전송이 확실한 일시 실패(연결 수립 실패·429 → `Retry-After` 반영) / 영구(`ok:false`의 인증·권한·채널 오류) / 결과 불명.
- **1단계 후속 과제 흡수**: 재시도 안전성에 직결되는 항목을 여기서 처리한다. codex WATCH 2건(제출 후 예약 실패를 Unknown으로 분류하지 않는 문제, 요청 준비 시간을 예산에서 빼지 않는 문제)과 §2.11 LOW 중 `SlackClient` 항목이다.
- **전이표**(최초 1회 + 재시도 3회 = 최대 4회 실행):

| 오류 | 마지막 시도 전 | 마지막 시도 |
|---|---|---|
| LLM 재시도 가능 | 안내 없이 `RETRY_WAIT(gen+1)` | 최종 안내 1회 |
| LLM 영구 | 즉시 최종 안내 1회 | 최종 안내 1회 |
| 답변 발신: 미전송 확실한 일시 실패 | `RETRY_WAIT(gen+1)` | `DEAD`+DLQ(안내 연쇄 없음) |
| 답변 발신: 영구 | `DEAD`+DLQ | `DEAD`+DLQ |
| 발신 결과 불명(답변·안내) | `UNKNOWN`+복구 목록, 재발신 없음 | 같음 |
| 최종 안내 성공 | — | `COMPLETED(kind=failure_notice)`, 정상 답변 지표에서 제외 |
| 최종 안내 명확한 실패 | — | `DEAD`+DLQ |

- 핸들러는 입력으로 `finalAttempt`를 받는다. `finalAttempt = retries ≥ retry.max-retries || manual_run`이다(gen으로 세지 않는다). 수동 승인 실행은 자동 재시도 없이 1회로 끝난다. 마지막 시도가 아니고 오류가 재시도 가능할 때만 안내를 보내지 않는다.
- `RetryPolicy`: 다음 세 가지를 Lua 한 번으로 처리한다. `ZADD slack:retry`(입력과 gen+1, 멱등) → `RETRY_WAIT(gen+1, retries+1, retry_at)` 기록 → XACKDEL. `finalize`와 같은 검증 → 멱등 쓰기 → 상태 → ACK 순서다. 재투입 메시지는 원래 `received_at`을 그대로 싣는다.
- 재시도 스케줄러: 도래한 항목에 대해 Lua 한 번으로 `ZRANGEBYSCORE` → **`XADD` 성공 후 `ZREM`** 순서로 처리한다(반대 순서면 `ZREM` 뒤 실패 시 입력이 사라진다). 두 명령 사이에서 실패하면 다음 주기에 다시 투입되는데, 중복 투입은 결과표(5행 `Busy`·3행 `Stale`·2행 `Done`)로 하나만 실행된다. `fail_after` 주입 테스트로 입력 보존과 중복 실행 0을 확인한다.
- 24시간 창: 선점 때 `Expired`로 판정하고 DLQ로 보낸다.
- **완료**: 전이표의 각 행을 유도해 B8·B9·B11을 확인하고, 중복 예약 투입 테스트를 통과한다.

### M14. 결과 불명 복구 (P1-3)
- **착수 전 멈춤(사용자 조작)**: Slack 앱에 스코프 `channels:history`(비공개 채널을 쓰면 `groups:history`)와 `reactions:write`를 추가하고 재설치한 뒤 토큰을 갱신한다. M15·M16에 필요한 스코프까지 한 번에 처리한다. 재설치 후 `auth.test`로 토큰의 스코프를 확인한다.
- 발신 시 `metadata`(`event_type=slack_lab_reply`, payload에 `event_id`·`attempt_id`)를 붙인다.
- 별도 `SENDING` 스위퍼는 두지 않는다. `SENDING` 상태의 메시지는 ACK되지 않으므로 pending에 남고, `XAUTOCLAIM`이 회수해 결과표 4행으로 `UNKNOWN`이 된다(B5). 따라서 보존본도 한 번만 생긴다.
- `recovery` 역할(CLI, 웹 포트 없음). **자동 재발신 경로는 없다.**
  - `list`: `slack:recovery`·`slack:dlq` 목록(`UNKNOWN`·`DEAD`).
  - `check <event_id>`: `conversations.replies`에 `include_all_metadata=true`를 붙여 스레드에서 해당 event_id의 봇 답글을 찾는다.
  - `resolve-completed <event_id> <ts>`: 상태를 `COMPLETED`(ts 기록)로 바꾼다.
  - `reprocess <event_id>`: 미전송이 확인된 경우에만 사람이 실행한다. Lua 한 번으로 `RETRY_WAIT(gen+1, retry_at=now)`·`manual_gen=gen+1` 기록 → 보존본으로 `XADD`(gen+1, 원래 `received_at` 유지). 보존 해시·목록은 그 실행이 `COMPLETED`가 될 때 지운다(실패하면 보존본이 그대로 복구 대상에 남는다). 승인은 그 gen의 첫 선점에서 소비되고 `manual_run`이 남는다. 워커가 죽어 재전달되면 24시간 이내·초과와 무관하게 결과표 4'행으로 `DEAD`가 되어 새 승인을 기다린다(B11).
  - `close <event_id>`: 상태를 `CLOSED`로 바꾼다.
  - `resolve-completed`·`close`는 보존 해시와 목록 항목을 삭제한다(B18).
- 실험 스위치 `experiment.halt-after-send`: 발신 성공 직후 `Runtime.halt`한다. slow-mode와 같은 실험용 스위치다.
- `scripts/p1-residue-check` 작성(B18).
- **완료**:
  - B5 전체 절차를 확인한다. 실제 스레드에서 halt를 유도하고, XAUTOCLAIM → UNKNOWN을 거쳐 `check`로 metadata를 찾은 뒤 `resolve-completed`까지 진행한다.
  - 미전송 건은 `reprocess` 1건을 확인하고, 24시간 초과 건의 자동 차단과 수동 승인 실행을 각각 확인한다(B11).
  - residue-check가 0이다.

### M15. 즉시 반응 (P1-5)
- **별도 반응 스트림** `slack:reactions`: 수신 서버의 발행 Lua가 `slack:events`와 함께 최소 항목(`event_id`·`channel`·`ts`·`received_at`, 본문 없음)을 한 번에 `XADD`하고, `WAITAOF`는 한 번만 호출한다. 처리 그룹의 XDEL과 무관하므로 답변이 먼저 끝나도 반응이 누락되지 않는다. 재시도·`reprocess` 재투입은 반응 스트림에 쓰지 않는다.
- 반응 소비자(`app.role=reactor`, 또는 워커 프로세스 안의 독립 스레드)가 `reactions.add`(`eyes`)를 호출한다. `already_reacted`는 성공으로 본다. 실패하면 로그를 남긴다. 보조 기능이라 재시도하지 않는다. 처리가 끝나면 자기 항목을 XACKDEL한다. 반응 소비자가 죽었을 때를 대비해 반응 그룹에도 `XAUTOCLAIM`(짧은 min-idle, 예: 10초)을 돌린다. 반응 항목에는 본문이 없어 PRD §7 대상은 아니지만, 잔존 항목 수도 residue-check에 포함한다.
- `reaction_ms`(최초 수신 → 반응 성공)를 로그에 남긴다.
- **완료**: 실제 멘션으로 확인한다. (a) LLM 워커를 멈춰 적체를 만든 상태 (b) 반응 소비자를 지연시켜 답변이 먼저 끝나는 상태 각각에서 B12(p95 ≤ 3s, 누락 0)를 확인하고, 스코프 오류를 유도해 답글이 정상인지 확인한다.

### M16. 스레드 문맥 (P1-6)
- 워커는 `thread_ts`가 있으면 `conversations.replies`로 이전 메시지를 가져온다. 한도는 `context.max-messages`(기본 10)와 `context.max-chars`이며, 봇 메시지는 assistant, 사람 메시지는 user 역할로 넣는다. 현재 메시지는 빼서 중복을 막는다.
- 조회 기한은 3초이고 LLM 단계 50초 예산에 포함된다. 실패하거나 시간을 넘기면 문맥 없이 진행하고 로그를 남긴다.
- `LlmClient` 입력을 메시지 목록으로 확장한다. OpenAI 호환 `messages` 형식을 그대로 쓴다(규칙 3).
- 프롬프트와 본문은 로그에 남기지 않는다. 문맥 메시지 수와 글자 수만 기록한다.
- **완료**: 실제 스레드에서 두 번째 질문의 답이 앞 대화를 반영한다(스크린샷·로그). 조회 실패를 유도해도 답변이 정상이다.

### M17. 관측 + 다중 워커 (P1-7·P1-8)
- 로그 필드를 통일한다.
  - 공통: `event_id`·`attempt_id`·`gen`·`stage`·`result`
  - 수신: `recv_ms`·`enqueue_ms`
  - 워커: `queue_wait_ms`·`llm_ms`·`send_ms`·`answer_ms`
  - 반응: `reaction_ms`
- 프로세스 간 구간은 UTC epoch ms로, 프로세스 안 구간은 단조 시계로 잰다. 음수 구간이 하나라도 있으면 해당 배치의 성능 측정은 무효다.
- 워커가 10초마다 적체 스냅샷을 로그에 남긴다: `XLEN`, pending 수, `ZCARD slack:retry`, `ZCARD slack:dlq`·`ZCARD slack:recovery`.
- 실패율은 결과별 건수를 grep으로 센다. 지표 라이브러리는 도입하지 않는다(M7 결정 승계). `EXPERIMENT-LOG.md` §4에 P1 집계 명령을 추가한다.
- `docker compose --profile app up --scale worker=2`로 워커 2개를 동시에 실행해 B15를 확인한다. `docker kill -s KILL`로 한 워커를 죽였을 때 다른 워커가 회수하는지도 본다.
- **완료**: 집계 명령으로 실제 로그에서 각 지표 값이 나오고, B15가 성립한다.

### M18. P1 검증 실험
- 부하 생성기 `scripts/p1-load`: 서명된 합성 이벤트를 보낸다. 실제 테스트 채널과 실제 부모 메시지의 ts를 쓰고, event_id는 합성한다.
- **P(성능)**: M9 고정 환경에서 워밍업 후 10 동시 × 10 배치를 실행한다. 배치가 모두 끝난 뒤 다음 배치를 시작한다.
  - 수신 p95·답변 p95·유실·실패·중복으로 B16을 판정한다.
  - 실패 안내·누락·429로 인한 실패는 정상 답변으로 세지 않고 불합격 사유로 기록한다.
  - 콜드 스타트는 따로 기록한다.
  - 환경 표에 빠진 값이 있으면 측정을 시작하지 않는다.
- **D(중복)**: D1 동일 event_id 10회 동시 전달, D2 완료 후 재전달, D3 서로 다른 10건. 워커 1개와 2개에서 각각 실행해 B6·B7을 판정한다.
- **R(복구)**: R1 큐 저장 후 수신 컨테이너 `docker kill -s KILL`, R2 처리 중 워커 컨테이너 kill, R3 발신 직후 halt로 B3·B4·B5를 판정한다. 성능 측정과 분리하고, ngrok은 켜 둔 채로 진행한다.
- 결과는 `EXPERIMENT-LOG.md`에 남긴다. **"검증 수행 완료"와 "P1 합격"을 구분해 적는다.** 합격한 항목만 PRD §6 2단계 체크박스에 체크한다. 미달이면 원인 단계(큐 대기·LLM·발신·429)를 특정하고 개선안 또는 목표 변경안을 사용자에게 올린다.
- **완료**: 세 실험을 모두 수행하고 판정을 기록한다. 2단계 완료는 합격 이후에만 가능하다.

## 4. 리스크와 대응

| 리스크 | 대응 |
|---|---|
| 기본 Ollama 설정에서 10 동시 추론 시 답변 p95 30초 미달 | M9에서 먼저 측정한다. 미달이면 멈추고 사용자에게 목표·환경 변경안을 올린다 |
| `XADD` 반환만 보고 내구성이 있다고 착각 | `WAITAOF` numlocal=1 검사, `kill -9` 실증. 주장은 프로세스 크래시 범위로 한정 |
| fsync가 수신 p95 200ms를 잠식 | M9에서 `XADD+WAITAOF` p95를 재고 `enqueue-timeout-ms`를 정한다 |
| 완료 기록 없이 ACK해 `SENDING`이 고착 | 워커가 반환값을 확인한 뒤에만 ACK(B10). 미ACK 메시지는 XAUTOCLAIM이 회수해 결과표로 `UNKNOWN` 처리 |
| 상태 전이와 입력 보존 사이 중단으로 복구 입력 유실(Lua는 롤백 없음) | 보존 → 상태 → ACK 순서 + 멱등 보존 + 결과표 2'행, 명령 사이 오류 주입 테스트(B9) |
| 수동 승인이 재전달로 여러 번 실행 | 승인을 gen에 묶고 첫 선점에서 소비, `manual_run` 영속 표시, 소실 시 4'행으로 `DEAD`, 승인 실행은 `finalAttempt`(B11) |
| 재시도 재투입 중 실패로 입력 유실 | `XADD` 후 `ZREM`, 중복은 결과표로 억제 |
| 절전·GC로 임대가 만료된 뒤 성공 보고가 거절됨 | 같은 attempt의 `UNKNOWN→COMPLETED` 허용 |
| 재시도 중복 투입, 재전송(gen 0)과 예약이 경합 | 선점 결과표의 gen 비교, 스케줄러 Lua 원자 처리 |
| XAUTOCLAIM이 정상 처리 중인 메시지를 계속 회수 | `min-idle ≥ 총 기한 + 임대`를 불변식으로 검사(기본 100초). 대가로 죽은 워커 회수에 최대 약 100초가 걸린다(성능 측정과 분리) |
| Slack 429가 10 동시 배치의 답변 시간을 오염 | M9에서 관측해 채널 분산 여부와 판정 규칙을 확정한다. 429 건수는 따로 기록한다 |
| 적체 때 "확인 중" 표시가 늦거나 누락 | 별도 반응 스트림으로 분리하고, 적체·답변 선완료 두 상태에서 B12를 검증한다 |
| 재시작 실험 중 ngrok URL이 바뀜 | ngrok은 유지하고 수신 서버만 재시작한다. URL이 바뀌면 멈춤 지점 |
| 복구 CLI가 공개 엔드포인트로 노출 | 웹 포트가 없는 `recovery` 역할로만 제공 |
| Docker Desktop이 측정값에 끼는 오버헤드(가상화 네트워크·자원 제한) | M18 성능 실험은 compose 환경으로 고정하고 Docker Desktop 버전·CPU·메모리 할당을 환경 표에 기록한다. Ollama는 호스트에서 돈다 |
| 컨테이너에서 호스트 Ollama 접근 실패 | `host.docker.internal` 사용, M10 완료 조건에서 컨테이너 안 호출을 확인한다 |

## 5. 검증 요약

- 단위: 오류 분류, 전이표(`finalAttempt` 분기), `RetryPolicy` 백오프·창, 컨트롤러 200/503, 역할별 빈 구성.
- 통합(Testcontainers Redis): 선점 결과표의 각 행과 우선순위 조합, 임대·만료·늦은 완료, 발행→소비→확정→ACK 순서, `finalize` 원자성, 저장 실패 시 ACK 없음, XAUTOCLAIM 회수, 스케줄러 중복 투입, 24시간 자동 차단·수동 승인, 잔존 본문 0.
- 외부 왕복: 실제 멘션 → 큐 → 워커 → 이모지·스레드 답글, 스레드 2턴 문맥, 복구 CLI `check`.
- 오류 유도: Redis 중단(503), 수신 서버·워커 kill, 발신 직후 halt, Ollama 중단, `ok:false`, 연결 끊김, 스코프 누락, 429.
- 실험: P·D·R 배치. 결과와 판정은 `EXPERIMENT-LOG.md`에 남긴다.

## 7. RALPLAN-DR 요약 (short mode)

**Principles**
1. **수락은 저장 확인 뒤에만 한다.** 200은 fsync가 확인된 큐 저장 뒤에, XACK는 상태·예약·DLQ·복구 기록의 저장 확인 뒤에 보낸다(규칙 11).
2. **정확히 한 번을 주장하지 않는다.** 결과 불명은 자동 재발신하지 않고, 입력을 보존한 채 복구 대상으로 남긴다.
3. **핸들러는 HTTP와 큐 ACK를 모른다.** 결과 타입과 호출부 변경만 허용한다. 종료 상태 기록은 워커가 한다(규칙 2).
4. **단계를 앞지르지 않는다.** RAG·LangGraph·n8n·지표 스택을 넣지 않는다(규칙 1).
5. **완료 = 외부 왕복 + 오류 유도 + 고정 환경 실측.** 검증 수행과 합격을 구분한다(규칙 5·12).

**Decision Drivers (상위 3)**
1. 저장 확인·상태 전이·ACK 순서를 **원자적으로** 보장할 수 있는가(재시작·재전달·다중 워커)
2. **큐 고유의 현상**(재전달·pending·소비 그룹·DLQ)을 직접 관찰하고 설명할 수 있는가 — "왜 큐인가"의 학습 목표
3. 로컬 1인 환경에서 운영 대상을 최소화하면서 PRD §5 성능 목표를 **고정 환경에서 검증할 수 있는가**

**Viable Options — 큐·상태 저장소**

| | Q1 (채택): Redis Streams + Redis 상태 | Q2: RabbitMQ + Redis 상태 | Q3: PostgreSQL 테이블 큐(SKIP LOCKED) + 상태 |
|---|---|---|---|
| 장점 | 한 저장소 안에서 Lua로 상태·예약·ACK를 원자 처리한다. 소비 그룹·pending·XAUTOCLAIM으로 재전달을 직접 관찰할 수 있다. PRD §8 후보이자 P1-8 예시(Redis)와 일치한다 | publisher confirm·DLX·TTL 지연 재시도가 기본으로 있다. 브로커 개념의 정석 | 상태와 큐를 한 트랜잭션으로 묶는다. `synchronous_commit`으로 내구성이 명확하다. 재시도·임대가 컬럼 몇 개로 끝난다. P2 pgvector와 저장소를 공유한다 |
| 단점 | 지연 재시도(ZSET)·DLQ·회수·gen CAS를 직접 만든다(작은 잡 프레임워크). `appendfsync always`는 실무에서 드문 설정이다. 큐와 상태가 같은 Redis에 묶여 교체 비용이 크다 | 운영 대상이 2개다. 큐 ACK와 Redis 상태 기록이 원자적이지 않아 경계 복구 로직이 늘어난다 | 소비 그룹·재전달 같은 **메시지 큐 고유 현상**이 테이블 폴링으로 가려져 Driver 2가 약하다. 폴링 주기가 큐 대기 시간과 수신 p95 해석에 섞인다. PRD §8 후보 밖이라 목표 조정이 필요하다 |

Q3는 기술적으로 타당한 대안이다. 기각 사유는 요구 위반이 아니라 Driver 2(학습 목표)다. Q1의 대가(직접 구현 부품)는 M11~M14에 명시적으로 배분했다.

**Viable Options — 프로세스 구성**

| | T1 (채택): 한 코드베이스, `app.role`로 수신·워커·반응·복구 분리 실행 | T2: Gradle 멀티모듈(receiver·worker) | T3: 워커만 Python |
|---|---|---|---|
| 장점 | 기존 핸들러·클라이언트·테스트를 재사용한다. 프로세스별 kill 실험이 가능하다 | 의존 경계를 컴파일로 강제한다 | 4단계 LangGraph로 옮기기 쉽다. 큐가 언어 경계가 된다 |
| 단점 | 역할별 빈 조건 관리가 필요하다(B2 테스트로 방어) | 1인 학습에 비해 빌드 구성 비용이 크다. 지금 공유 코드가 대부분이다 | 검증된 Java 핸들러·기한 강제(M1.5)·Slack 분류를 다시 구현해야 하고 운영 런타임이 2개가 된다. 이득은 4단계에서야 생긴다 |

T3는 규칙 위반으로 기각한 것이 아니다. 비용 대비 이득 시점이 맞지 않아 4단계에서 다시 검토한다(ADR-5).

**Viable Options — 지연 재시도**

| | R1 (채택): ZSET 예약 + Lua 재투입 | R2: pending에 두고 idle 시간으로 재시도 |
|---|---|---|
| 장점 | 백오프 5s·30s·120s를 정확히 지키고, 예약을 영속 저장한 뒤 ACK한다(§5.1) | 구현이 적다 |
| 단점 | 스케줄러와 gen 검사가 필요하다 | idle 하나로는 세대별 백오프를 표현할 수 없다. 예약 저장 없이 ACK를 미루는 방식이라 §5.1의 "예약 영속 저장 후 ACK"와 맞지 않는다 → 기각 |

## 8. ADR

- **Decision**: 2단계를 M9→M18로 진행한다. 구성은 다음과 같다.
  - 큐: Redis Streams(AOF always + `WAITAOF` 확인, 프로세스 크래시 내구성).
  - 공유 상태: 같은 Redis의 Lua CAS·임대·gen, 선점 결과표.
  - 워커: 같은 Java 코드베이스의 `app.role` 프로세스. 종료 상태는 워커가 기록하고 ACK한다.
  - 실행: Redis와 앱 역할들은 Docker Compose, Ollama는 호스트(로컬 이미지 없음·모델 용량).
  - 지연 재시도: ZSET. 즉시 반응: 별도 반응 스트림.
  - 종료 처리: `finalize`가 검증 → 멱등 보존 → 상태 → ACK 순서로 처리한다(Lua 무롤백 전제).
  - 결과 불명: 입력을 보존하고 `recovery` CLI에서 사람이 승인해야만 처리한다.
- **Drivers**: 저장 확인·전이·ACK의 원자성 / 큐 고유 현상의 관찰 가능성 / 로컬 운영 부담과 성능 검증 가능성.
- **Alternatives considered**:
  - RabbitMQ(Q2): 운영 대상 2개, 경계가 비원자적.
  - PostgreSQL 큐(Q3): 타당하지만 큐 고유 현상 관찰이 약하고, PRD §8 후보 밖이다(Architect steelman 반영).
  - 멀티모듈(T2): 구성 비용.
  - Python 워커(T3): 재구현 비용, 이득은 4단계에야 생긴다.
  - idle 기반 재시도(R2): §5.1 ACK 규칙과 맞지 않는다.
- **Why chosen**: Redis 하나로 P1-2(큐)와 P1-8(프로세스 밖 상태)을 함께 만족한다. 상태·예약·ACK를 Lua 한 번으로 묶어 §5.1 ACK 규칙을 가장 적은 경계로 지키면서, 재전달·pending을 직접 관찰할 수 있다.
- **Consequences**:
  - DLQ·지연 재시도·회수·원자 종료 스크립트를 직접 구현한다(M11~M14에 배분).
  - Redis가 단일 장애점이다. 장애는 503으로 드러난다(PRD §5 가용성).
  - 죽은 워커의 메시지 회수에 최대 약 100초가 걸린다.
  - 내구성 주장은 프로세스 크래시 범위다.
  - 워커 언어(ADR-5)는 4단계에서 다시 검토한다.
- **Follow-ups**:
  1. M9 실측으로 성능 목표 달성 가능성을 판정한다. 불가하면 사용자가 결정한다.
  2. 이모지 완료 교체와 동시 100건 검증은 P1 이후로 미룬다.
  3. 4단계 LangGraph 도입 때 워커 언어를 다시 검토한다.
  4. 착수 조건에서 2단계 Git 정책을 AGENTS.md에 기록한다.

## 9. Changelog (Consensus)

- **1회전 Architect(REQUEST CHANGES) 반영**
  - 완료 기록을 확인한 뒤에만 ACK: 워커가 종료 상태를 기록하고 반환값을 검사한다. `markCompleted(ts)`로 바꾸고 `SENDING` 스위퍼를 추가했다.
  - 같은 attempt의 `UNKNOWN→COMPLETED` 허용.
  - 선점 결과표와 gen 규칙, 스케줄러 Lua 원자 처리.
  - `FAILED`를 `RETRY_WAIT`/`DEAD`로 분리.
  - 발신 일시 실패도 재시도 대상에 포함.
  - UNKNOWN 입력을 복구 스트림에 보존.
  - 내구성 주장을 프로세스 크래시로 한정하고, `WAITAOF` 반환값 검사·raw 명령·Redis 시각 사용을 명시.
  - `min-idle ≥ 총 기한 + 임대`.
  - `first_received_at`은 최소값으로 유지하고 Redis 시각으로 판정.
  - B2 빈 범위 확대.
  - 스코프 재설치 멈춤을 M14(복구) 착수 전으로 이동, 기존 M13을 재시도(M13)와 복구(M14)로 분리.
  - 워커 2개는 `java -jar`로 실행.
  - 429 판정 규칙을 M9에서 확정.
  - Q3 steelman을 반영해 기각 근거를 Driver 2로 재서술.
- **1회전 Critic(codex, ITERATE) 반영**
  - M12 임시 정책에서 `Unknown`/`Rejected`를 재시도하지 않게 하고, 발신 직후 장애·소유권 상실 확인을 M12 병합 조건에 넣었다.
  - 핸들러–워커 계약 명시.
  - B18 삭제 경로를 정의하고 residue-check 추가.
  - 오류별 전이표 작성.
  - 측정 경계(최상위 필터), `received_at` 불변 전달, 시계 이상 시 측정 무효 처리.
  - P0 Ollama 환경 유지와 탐색 측정 분리.
  - M11/M12 의존 순서 정리: 호출부 전환은 M12에서.
  - 즉시 반응을 최초 수신 기준으로 바꾸고 별도 소비 그룹으로 분리, 적체 상태에서 검증.
  - 2단계 Git 정책을 착수 조건에 넣고, 검증 수행과 합격을 구분.
  - T3·Q3 기각 근거를 공정하게 다시 씀.
- **2회전 Architect(APPROVE-WITH-IMPROVEMENTS)·Critic(codex, ITERATE) 반영**
  - 불변식과 기본값 충돌 해소: `≥`로 통일, 기본 `claim-min-idle-ms=100000`, `renew-ms ≤ lease-ms/3`, 기본값 기동 성공 테스트.
  - 선점 결과표를 우선순위표로 재작성: 만료 `SENDING`→`UNKNOWN`이 24시간 판정보다 우선, 종료 상태는 `Done`, 24시간 판정은 실행 가능한 행에만 적용, m>s는 불변식 위반(`Anomaly`)으로 처리.
  - `finalize` 원자 스크립트로 전이·입력 보존·ACK를 묶고 경계 kill 테스트 추가. 늦은 `UNKNOWN→COMPLETED` 때 복구 항목 삭제.
  - M11은 P1 전용 타입만 추가하고, 기존 계약 교체와 호출부 전환은 M12에서 함께 한다.
  - 즉시 반응을 별도 반응 스트림(본문 없음)으로 바꿔 XDEL 경쟁을 없애고, 답변 선완료 상태도 검증.
  - `reprocess`가 상태 gen을 먼저 올린 뒤 투입하고 `manual_until`로 24시간 초과 건의 수동 실행을 허용.
  - 스위퍼 제거: 미ACK `SENDING`은 XAUTOCLAIM과 결과표로 `UNKNOWN`이 된다(복구 사본 중복 없음).
  - M12~M13 사이 DLQ 확인 방법과 일시적 퇴행을 명시.
- **3회전 Architect(APPROVE-WITH-IMPROVEMENTS)·Critic(codex, ITERATE) 반영**
  - Lua 무롤백 전제로 `finalize` 재설계: 검증 먼저 → 멱등 보존(`slack:preserved:{event_id}` 해시 + ZSET 목록) → 상태 → ACK. 결과표 2'행(보존 누락 복구) 추가, 명령 사이 오류 주입 테스트로 교체.
  - DLQ·복구 저장을 스트림에서 해시+ZSET으로 바꿔 재실행 시 중복 사본을 없앰.
  - 수동 승인을 `manual_gen`으로 gen에 묶고 첫 선점에서 소비. 승인 실행은 1회(`finalAttempt`), 실패·소실 시 복구 대상으로 복귀.
  - 시도 횟수를 gen과 분리한 `retries`로 셈.
  - 반응 그룹에 `XAUTOCLAIM` 추가, `WAITAOF`를 Lua 밖에서 호출한다고 명시.
- **4회전 Architect(APPROVE-WITH-IMPROVEMENTS)·Critic(codex, ITERATE) 반영**
  - 재시도 스케줄러 순서를 `XADD` 성공 후 `ZREM`으로 바꾸고 중복 투입은 결과표로 억제.
  - `manual_run`을 영속 표시하고, 승인 실행이 소실되면 24시간과 무관하게 `DEAD`(결과표 4'행).
  - 해결된 건 재보존 방지: `resolve-completed`→`COMPLETED`, `close`→`CLOSED`, 2'행은 m == s로 한정.
  - 오류 주입을 테스트 전용 `fail_after=N`으로 명시(검증 단계가 가로채는 모순 해소).
  - 1행은 상태가 없으면 건너뜀.
- **5회전 Architect(APPROVE)·Critic(codex, ITERATE) — 최대 반복 도달, Planner 최종 반영(재검토 없음)**
  - Critic MAJOR: `XACK` 뒤 `XDEL` 전에 실패하면 본문이 pending 밖에 남아 회수되지 않는다 → 모든 `XACK+XDEL`을 Redis 8.2 단일 명령 `XACKDEL`로 교체(원자, 틈 없음). M9 스파이크에서 `XACKDEL` 동작을 함께 확인한다.
  - Architect 참고: `COMPLETED`/`CLOSED` 7일 만료 이후의 같은 event_id 재유입은 새로 실행된다(PRD §5 보존 정책과 일치). M18 기록에 "중복 억제 보장 범위 7일"로 명시한다.
