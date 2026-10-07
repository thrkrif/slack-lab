# 작업 계획 — slack-lab

**1단계(P0): 완료** — `main` 병합(#17), 태그 `v0.1.0`
**2단계(P1): 완료(`v0.2.0`) — 이후 ADR-9 기반 전환 M19~M24는 `develop`에서 완료**
**4단계(요청 분류 + 모델 역할 분리): 진행 중(2026-10-06 착수) — 계획 M31~M36은 아래 `# 4단계 계획`**
**3단계(RAG): 완료(`v0.3.0`, 2026-10-06) — 구현·검증 M24.5a~M30(2026-10-04) — 합격선 충족, 컨테이너 전체 스택·알람형 왕복·중복/언어 재검증 완료(EXPERIMENT-LOG §37). 실제 Slack 이벤트→ngrok→수신→RAG 답글·👀 반응도 1건 확인(EXPERIMENT-LOG §38) ** — 아래 `# 3단계(RAG) 계획` 참조
2단계 계획 승인 이력: ralplan consensus 5회전(Architect 최종 APPROVE, Critic(codex) 최종 ITERATE → 마지막 지적 1건은 Planner가 반영, 재검토 없음). 사본: `.omc/plans/stage2-p1-plan.md`
정본: 이 파일. 1단계 상세 계획은 아래 **부록**에 보존한다(1단계 사본 `.omc/plans/stage1-p0-plan.md`).
근거: [`PRD.md`](PRD.md) §4·§5·§7, [`ARCHITECTURE.md`](ARCHITECTURE.md), [`AGENTS.md`](../../AGENTS.md)
범위: 2단계는 **P1-1 ~ P1-8만**(완료). 3단계는 **RAG(P2-1~P2-4)만**(완료). 4단계는 **P2-5·P2-6만**(Java 워커 안의 요청 분류·모델 역할 분리)이며 LangGraph·n8n·지표 스택은 넣지 않는다(AGENTS.md 규칙 1).

## 진행 상태

새 세션은 이 절과 `docs/EXPERIMENT-LOG.md`를 먼저 읽는다. 마일스톤을 끝낼 때마다 갱신한다.

### 1단계 (P0) — 완료

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
- [x] **M7 계측** — 완료. 로그 grep 집계 방법을 문서화(`docs/EXPERIMENT-LOG.md` §4). M8 파일럿 로그로 실제 검증 완료, 버그 1건(재전송 사유 집계가 `retry_reason=null`도 같이 셈) 발견·수정
- [x] **M8 실험** — 완료
  - [x] 재전송+dedup 억제 관측(§5.1): Slack이 정확히 3.0초에 재전송, dedup이 즉시 막아 답글 1건만 나감
  - [x] 지연값 스윕(§5.1.1): 1000·2000ms(재전송 0/3) vs 2500·3000·5000·30000ms(재전송 다수) — 실제 경계는 2000~2500ms 사이, PLAN 원안의 "2.5초는 안전" 가정이 틀렸음을 실측으로 발견(원인: dedup·발신 네트워크 오버헤드가 slow-mode 위에 얹힘)
  - [x] `experiment.dedup-enabled=false`로 실제 중복 답글 재현(§5.2, M8-4): 같은 event_id가 다른 attempt_id로 독립 처리돼 채널에 댓글 — "왜 큐가 필요한가"의 직접 증거. 재시작 타이밍이 겹쳐 인메모리 dedup이 재시작에서 살아남지 못하는 P1 한계도 부수적으로 실측(§5.2 정정 내용)
  - [x] 실제 LLM 실험은 A1(§2.9)으로 대체 확인, 재유도 없음(§5.3)
  - [x] 오류 유도 매트릭스(A2·A5·A6·A7·A8·A10·A15·A16) — 이미 확보된 근거로 §5.4에 표로 정리
  - [x] M8-5 선택 배치(§5.6): 기한을 90/100초로 늘려 70초 지연도 정상 응답됨을 확인 — 기본 50초 하드캡이 정상 응답을 실패로 오분류할 수 있음을 실측
  - [x] PRD §6 1단계 체크박스 3개 모두 이 실측 근거로 체크 완료
- [x] **1단계 완료** — `develop`→`main` 병합(#17), 태그 `v0.1.0`. `AGENTS.md` 현재 단계 줄은 2단계 계획 승인 PR에서 갱신

### 2단계 (P1) — 진행 중

- [x] **착수 조건** — (1) 1단계 완료(`v0.1.0`) (2) 계획 승인·2단계 Git 정책(1단계와 동일하게 작업 단위마다 자동 PR·`develop` 병합)·Docker Compose 실행 결정(2026-09-28 사용자). `AGENTS.md` 반영
- [x] **M9 착수 결정 + 타당성 스파이크** — 완료
  - [x] ADR-5 결정·ADR-8 추가, PRD §8 큐·워커 질문 해소
  - [x] Redis 스파이크: `XADD+WAITAOF` p95 3.18ms, numlocal=1, `XACKDEL` 단일 명령, `docker kill` 후 보존 (`EXPERIMENT-LOG.md` §6.1)
  - [x] Ollama 타당성: 10 동시 p95 272.8s, 순차 처리량 8.8 tok/s → **답변 p95 30s 목표는 이 환경에서 불가**. 워커 LLM 동시성 1로 확정 (§6.2)
  - [x] 목표 변경 결정(2026-09-28 사용자: 노트북 부하를 줄이는 방향) → 순차 20건(답변 p95 ≤ 45s) + 버스트 5건×2(정확성만), PRD §5·§6 반영
  - [x] Slack 429: 5·10건 동시 발신 모두 429 없음 → 테스트 채널 1개 유지 (§6.3)
- [x] **M10 인프라·설정 골격** — 완료. `compose.yaml`·`Dockerfile`·`infra/redis.conf`, `app.role` 역할별 빈(B2), 설정 record 4종·불변식, `/health` Redis. 컨테이너 안에서 호스트 Ollama 호출 확인 (`EXPERIMENT-LOG.md` §7)
- [x] **M11 공유 처리 상태 저장소** — 완료(codex 2회전 반영, 3회전은 usage limit으로 code-reviewer 대체). `state/` 패키지·`state.lua`, 선점 결과표 9종, `finalize` 검증→보존→상태→ACK 순서, 세대 인식 정리. 통합 테스트 26건 (`EXPERIMENT-LOG.md` §8)
- [x] **M12 수신–큐–워커 분리** (P1-1·P1-2) — 완료(PR #22). code-reviewer(codex 대체) REVISE(MAJOR 3건) → MAJOR-1(임대 갱신 미구현)·MAJOR-2(Redis 타임아웃 없음) 수정·회귀 테스트·실측 반영, MAJOR-3(왕복 근거 부풀림) 문서 정정 후 서버 재기동해 재확인(200→큐→워커→LLM→Slack 발신 성공→Delivered). B3·B4(역할 분리 kill 시나리오)는 `app.role=all` 단일 프로세스로 재현 불가해 M17(다중 워커)로 이월. 테스트 133건(MAJOR-1 회귀 테스트 추가 포함) (`EXPERIMENT-LOG.md` §9)
- [x] **M13 재시도·DLQ** (P1-4) — 완료(PR #23). 오류 분류(LlmResult·SlackSendResult에 retryable), `state.lua` RETRY_WAIT 전이·`retry_scheduler.lua`(XADD 후 ZREM), `RetryPolicy`·`RetryScheduler` 신규. codex critic 1회전 REVISE(MAJOR 2)→반영, 2회전 usage limit→code-reviewer 대체→MAJOR-A(재시도 보존 부분실패 잔여 경합) 발견·반영. 전이표 각 행 통합 테스트로 유도, M11 DLQ·복구 회귀 없음 확인. 테스트 163건 (`EXPERIMENT-LOG.md` §10)
- [x] **M14 결과 불명 복구** (P1-3) — 완료. 스코프(`channels:history`·`reactions:write`)는 이미 반영돼 있어 `auth.test` 헤더로 확인 후 진행. 발신 metadata, `experiment.halt-after-send`, `state.lua` `resolve`·`reprocess` op, `recovery` CLI(`scripts/recovery`), `scripts/p1-residue-check`. B5·B11·B18 실측 (`EXPERIMENT-LOG.md` §11)
- [x] **M15 즉시 반응** (P1-5) — 완료. `publish.lua`(두 스트림 원자 XADD), `ReactionConsumer`, `SlackClient.addReaction`, residue-check에 반응 스트림 추가. B12 실측 (`EXPERIMENT-LOG.md` §12)
- [x] **M16 스레드 문맥** (P1-6) — 완료. `LlmMessage`·`LlmClient.chat(List)`, `ThreadContextSource`(event)·`SlackThreadContext`(slack)·`SlackThreadClient.fetchMessages`. 실제 스레드에서 문맥 반영·조회 실패 시 정상 답변 확인 (`EXPERIMENT-LOG.md` §13)
- [x] **M17 관측 + 다중 워커** (P1-7·P1-8) — 완료. `처리 지표` 통합 로그·`BacklogReporter`·`scripts/p1-metrics`, 워커 2컨테이너에서 D1·D3(B15), B3·B4 kill 실측 (`EXPERIMENT-LOG.md` §14)
- [x] **M18 P1 검증 실험** — 완료. `scripts/p1-load`, P(순차 20·버스트 5×2)·D(워커 1·2개)·R(R1·R2·R3) 수행, 판정 합격(합성 요청 기준·한계는 `EXPERIMENT-LOG.md` §15.5). PRD §6 2단계 세 항목 체크
- [x] **2단계 완료** (`develop`→`main`, 태그 `v0.2.0`, 현재 단계 줄) — 사용자 요청으로 2026-09-30 전환 — **P1 합격(또는 사용자가 승인한 목표 변경 후 합격) + 사용자 요청 시에만**

각 마일스톤을 마칠 때 이 목록, `다음 세션 핸드오프` 소절(덮어쓰기), `EXPERIMENT-LOG.md`를 갱신한다. 구조가 바뀐 마일스톤(M12·M13·M14·M15)은 같은 작업에서 `ARCHITECTURE.md`도 갱신한다(규칙 9).

### 3단계 (P2: RAG) — 진행 중

- [x] **M24.5a 스파이크** — 완료(`docs/spikes/rag-embedding-spike.md`, `EXPERIMENT-LOG.md` §24): `/v1/embeddings` 지원, bge-m3 1024차원·정규화, 콜드 1.2~2.2s·웜 0.05s, 673MB
- [x] **M24.5b 계약 확정** — 완료(`EXPERIMENT-LOG.md` §25): `VectorStore`(전체 재색인 대기 세대 포함)·`EmbeddingClient`·`DocumentSource`·`IndexLock`, 결과 타입 `EmbeddingResult`·`SearchResult`·`PortResult`, `ReferenceList`. codex 리뷰 MAJOR 2 반영
- [x] **M25 pgvector 기반** — 완료(`EXPERIMENT-LOG.md` §26): `pgvector/pgvector:pg16`, 테스트 5개 이전, V3, `PostgresVectorStore`(검색 기한·취소 오류 유도 통과, compose 이미지 확인). codex 리뷰 MAJOR 3 반영. **기존 로컬 볼륨은 사용자가 백업 후 초기화해야 한다**
- [x] **M26 임베딩 어댑터·스위치·기동 검사** — 완료(`EXPERIMENT-LOG.md` §27): `RagProperties`·`RagGuard`·임베딩 클라이언트·`RagStartupCheck`·`AppRole.INDEXER`, 실제 Ollama bootRun으로 정상/차원·모델·외부 호스트 거부 유도, 기동 거부 시 큐 소비 0 확인. codex 리뷰 MAJOR 3 반영
- [x] **M27 색인 파이프라인** — 완료(`EXPERIMENT-LOG.md` §28): 로컬 마크다운 출처·청커·`IndexingService`·`scripts/rag-index`·advisory lock. 실제 Ollama로 증분·삭제·빈 폴더(exit 5)·임베딩 단절(exit 1)·동시 실행(exit 3)·`kill -9` 무결 확인. codex 리뷰 MAJOR 2 반영
- [x] **M28 검색 주입·출처·폴백** — 완료(`EXPERIMENT-LOG.md` §29): `RetrievalService`·`RagPrompt`·`ReplyFooter`·`SlackFooterRenderer`. 실제 Slack·Ollama·pgvector 왕복(합성 이벤트)으로 RAG 켬(근거·참고 문서)·무관 질문(근거 없음 안내)·끔·임베딩 단절 폴백 확인, **채팅 모델이 올라간 상태의 bge-m3 콜드 적재가 23초라 5초 상한을 넘어 폴백된다는 측정**과 임계값 0.5가 느슨하다는 관찰을 M30 입력으로 남김. 리뷰(`code-reviewer`, codex 한도) MAJOR 2 반영
- [x] **M29 평가 데이터·하니스** — 완료(`EXPERIMENT-LOG.md` §30): `docs/rag-eval/`(문서 20·final 30·tuning 10), `RagEvaluator`(hit@3·근거 미주입, 합격선 정수 경계 테스트; `AnswerFormatChecker`는 운영에서 안 쓰여 AI-slop 점검 뒤 삭제), `scripts/rag-eval`(기본 tuning, final은 명시, `POSTGRES_URL` 필수), 어휘 기준선 18/24는 정합성 확인일 뿐 판정 아님.
- [x] **M30 통합 실측·판정** — 완료·합격(`EXPERIMENT-LOG.md` §31): bge-m3 final hit@3 24/24·근거 미주입 6/6(nomic 19/24·4/6 불합격), 임계값 0.54(tuning 기준), 문맥 상한 1500(4K 잘림 방지), 3B p95 15.6s·7B p95 30.8s·한자/가나 혼용 0 → 권장 기본 bge-m3 + qwen2.5:3b
- [x] **3단계 완료** — `develop`→`main` 병합, 태그 `v0.3.0` (사용자 요청으로 2026-10-06 전환)

### 4단계 (P2: 요청 분류 + 모델 역할 분리) — 진행 중

- [x] **M31 스파이크** — 완료(`docs/spikes/classification-spike.md`, `EXPERIMENT-LOG.md` §39): 3b·7b 모두 15/20·장애 재현율 8/8(멈춤선 통과), **3b와 7b는 이 장비에서 동시 상주 불가**(서로 축출, 7b 재적재 8.6s) → 분류 모델 = `llm.model` 구성으로 계속, P2-6 의도 재결정은 사용자 몫
- [ ] **M32 데이터·라벨 정의·하니스·합격선 고정**(프롬프트보다 먼저), 분류 off 주입 기준선, 판정 구성 고정
- [ ] **M33 계약·분류 어댑터**(전송부 추출, `RequestClassifier`, `ClassificationStartupCheck`, `isAlert()`)
- [ ] **M34 핸들러 분기·단위**
- [ ] **M35 외부 왕복·오류 유도**
- [ ] **M36 통합 실측·판정**(ADR-10·PRD 체크·EXPERIMENT-LOG)
- [ ] **4단계 완료** — `develop`→`main` 병합·태그(`v0.4.0`)는 사용자 요청 시에만

### 다음 세션 핸드오프

단위를 끝낼 때마다 이 소절을 덮어쓴다. 재현 가능한 사실만 적고, 진행 체크는 위 목록이 정본이다.

- **상태(2026-10-07)**: M31 완료(§39). **사용자 결정(2026-10-07)**: 분류 = 답변 = `qwen2.5:3b`(분리는 16GB에서 축출로 불성립, 설정 슬롯만 유지). PLAN M32·M36·C8·PRD §4 반영 완료. **다음**: M32 데이터·라벨 정의·하니스·합격선 고정 — 라벨 정의 문서, `docs/rag-eval/classification.json`(tuning 30·final 60), `scripts/classify-eval`, 분류 off 주입 기준선(검색만; Docker pgvector 별도 compose + bge-m3, 끝나면 즉시 내림). 정보 부족 라벨이 약점(M31: 한 줄 모호 질문을 장애로 오판) → 라벨 정의·예시 보강. 로컬 `postgres-data` 볼륨은 사용자가 백업(`pg_dump`) 후 `docker compose down -v`로 초기화해야 한다(M36 전). 5단계·설치 자동화는 4단계 뒤.
- 실행: `docker compose up -d rabbitmq postgres`(.env에 `POSTGRES_PASSWORD`) + 호스트 `bootRun`, 또는 `docker compose --profile app up --build`. 호스트 8080을 옛 `bootRun`(Redis 시절 코드)이 점유하고 있을 수 있다 — 컨테이너는 `RECEIVER_PORT=18080`으로 띄웠다.
- 부하·실험은 노트북 부담이 크다(Docker + Ollama 약 8GB). 개발 중 검증은 가볍게, 전체 스택·LLM 실험은 마일스톤 끝에 한 번만 하고 바로 내린다(`docker compose down --remove-orphans`, `ollama stop qwen2.5:7b`).
- M22 실측: 순차 20건 수신 p95 71ms·답변 p95 6.4s, 버스트 수신 p95 129ms, D1~D3 중복 0, R1~R3 통과(R2 인수 64초, R3 자동 조회 완료). 언어 방어가 실제로 작동해 일부 질문은 재시도 뒤 실패 안내로 끝난다(§21).
- 클라이언트 Slack 호출만 가짜인 `FullStackWiringIT`·`RoleWiringIT`가 배선 회귀를 막는다. 테스트는 Testcontainers(postgres:16-alpine, rabbitmq:3.13-alpine).
- **세션 종료 시 호스트 `bootRun`/컨테이너를 켜둘지 끌지 사용자에게 명시적으로 알릴 것.**

---

# 4단계(요청 분류 + 모델 역할 분리) 계획
승인: 2026-10-06 사용자 요청으로 단계 전환. 합의 이력: ralplan consensus 4회전(Architect·Critic 각 4회, 최종 Critic APPROVE). 사본: `.omc/plans/stage4-plan.md`(untracked). 근거: PRD §4 "4단계 범위 확정(2026-10-06)", ARCHITECTURE ADR-5 재검토(LangGraph 보류). 
v1→v2→v3→v4: Architect(분류 전용 포트, 알람·스레드 식별, 예산)·Critic(데이터 선행, C3·C5 무력, 되묻기 후속, 미달 규칙) 반영. 변경 요약은 맨 아래(v3 = 배선 정리·판정 구성 고정·C5 웜 정의·C7 분리).

### 확정된 입력(재질문 금지)
요청 3종(장애 질문=검색+답변 / 단순 질문=검색 없이 답변 / 정보 부족=되묻기) · 분류·검색 판단은 작은 모델, 답변은 설정된 답변 모델(`llm.model`, 이 단계는 바꾸지 않는다) · 무관 문서 주입은 분류로 흡수(임계값 재튜닝 없음) · 벤더는 로컬 Ollama 다중 모델까지 · 반복 검색·사람 승인·질의 재작성 제외.

### RALPLAN-DR
**Principles**: (1) 분류는 부가 — 꺼도·실패해도 3단계 흐름 그대로(fail-open = 장애 질문 취급) (2) 조용한 실패 금지(분류 결과·소요·폴백 사유·시도별 라벨을 로그) (3) 60초 예산·노트북 자원 불변 (4) 코어는 포트만 안다, 모델 ID는 설정, 외부 벤더 불가 (5) 합격선 사전 고정(데이터·라벨 정의가 프롬프트보다 먼저), 오분류 비용은 비대칭(장애 질문을 놓치는 쪽이 더 나쁘다).
**Drivers**: ① 16GB 노트북 메모리(채팅 모델 2개+임베딩 동시 적재·축출·콜드 로드) ② 분류 정확도(3b가 라벨을 안정적으로 내는가) ③ 기존 `LlmClient`·재시도·폴백·푸터 계약과 충돌 최소.
**Options**:
- A (선택) **분류 전용 포트 `core.port.RequestClassifier`**(결과: `RequestKind | Failed`)를 두고 `adapter/llm`이 자체 프롬프트·모델·`max_tokens=16`·temperature 0·언어 재질문 없음으로 구현. 코어는 결과로 3갈래 분기하고, 되묻기 문장은 답변 모델이 쓴다. 장점: 두 번째 `LlmClient` 빈·한정자 불필요(`LlmConfig` 단일 빈·핸들러 타입 주입 유지), 답변 어댑터의 페르소나·temperature 0.3·한자/가나 재질문을 상속하지 않음. 단점: 라벨 파서 단위 테스트가 어댑터 쪽, LLM 호출 +1.
- B 분류기가 라벨+되묻기 문장까지 JSON 출력 → 3b JSON 신뢰도·언어 방어 우회로 기각.
- C 규칙·키워드 분류 → 한국어 자유 질문에 취약, P2-6 미충족으로 기각(알람 경로만 분류 생략으로 규칙 사용).
- D bge-m3 임베딩으로 라벨된 예시 질문과 유사도 분류(LLM 호출 없음, ms 단위) → 단순 질문과 장애 질문의 분리가 3단계에서 본 0.54 임계값 근처의 겹침 문제와 같고, 예시 유지가 필요하며, 합의된 "작은 모델이 분류"(P2-6)를 실행하지 못해 기각. 후속 후보(사전 필터)로만 남긴다.
**ADR(요약)**: Decision A. Consequences: 호출 +1·콜드 로드 위험, 모델 상주 증가 가능, 평가 세트 신규. **Architect 반론(분류기는 컨텍스트를 줄이기만 하고 추가하지 않는다, 3번째 모델 상주 비용이 이득보다 클 수 있다)에 대한 대응**: 분류 모델을 `llm.model`과 같게 두는 구성(추가 상주 0)을 합법 설정으로 허용하고, 구성 B의 C8이 실패하면 이 구성으로 되돌린다. Follow-ups: 질의 재작성(스레드 후속 검색 질의), 분류 결과 영속화, D 사전 필터, 5단계 지표.

### 설계 결정(구현 전 고정)
1. **포트·설정**: `RequestClassifier.classify(String question, long remainingMs)`. 설정 `ClassificationProperties`(`classification.enabled` 기본 false, `classification.model` enabled면 필수, `classification.timeout-ms` 기본 5000, `classification.keep-alive` 기본 `30m`)는 **어댑터 전용**이며 코어는 읽지 않는다(시간 상한 `min(timeout-ms, 남은 예산)`은 어댑터가 clamp). 배선은 RAG 선례를 따른다: `@ConditionalOnProperty(classification.enabled)` 설정 클래스 + 핸들러에 `Optional<RequestClassifier>` 주입(기존 7인자·8인자 생성자를 위임 생성자로 유지하고 `@Autowired`를 새 9인자 생성자로 옮겨 C1 테스트 무변경 — `SlackEventHandlerRagTest`가 8인자를 쓴다). 따라서 `ArchitectureTest`는 **바뀌지 않는다**. 기동 검사는 `StartupInvariants` 생성자를 넓히지 않고 `ClassificationStartupCheck`(`RagStartupCheck` 형식, `@ConditionalOnRole(WORKER, ALL)`)로 둔다. **큐 소비자는 이 검사가 끝난 뒤 시작해야 하므로** `RabbitConfig`의 `ObjectProvider<RagStartupCheck>`와 같은 방식으로 `ObjectProvider<ClassificationStartupCheck>`를 소비자 선행 조건에 추가한다(검사 거부 전 메시지 소비 방지): enabled인데 model 공백 거부, `llm.client=ECHO`와 enabled 조합 거부, 기동 로그에 `classification=on|off`. 분류 모델이 `llm.model`과 같으면 keep-alive는 `llm.keep-alive`를 상속한다(같은 모델에 두 값이 경합하지 않게). 분류기 워밍업은 자체 짧은 마감을 쓴다. 분류기는 `llm.base-url`을 공유한다 → 로컬 외 벤더는 구조적으로 불가.
2. **프롬프트·파서**: 분류 프롬프트는 어댑터 안(코어는 모름). 질문은 데이터 블록으로 감싼다(최악 영향=RAG 생략, 보안 경계 아님). 엄격 파싱, 그 외 출력·실패·타임아웃·예외 → `Failed` → 코어가 `TROUBLE`(fail-open) + 사유 로그.
3. **분류 생략(→TROUBLE)**: ① 알람 경로 — `SlackMessageEvent.isAlert()`를 한 곳에 정의(`ts==null && threadTs==null`, `AlertEvent.java:33`)하고 `SlackEventHandler.java:182`의 인라인 검사를 이것으로 교체 ② **스레드 안 이벤트 전부**(`threadTs != null`; 스레드 후속은 단독 질문이 아니라서 SIMPLE/NEEDS_INFO가 오분류를 낳는다. 되묻기에 대한 사용자 답변도 여기서 TROUBLE로 가서 스레드 문맥과 함께 검색·답변). 즉 분류는 **최상위 멘션**에만 적용된다.
4. **분기**: TROUBLE = 3단계 그대로. SIMPLE = 검색 생략, 푸터 없음. NEEDS_INFO = 검색 생략, 푸터 없음, 코어가 사용자 메시지에 "부족한 정보를 되묻는 한 문장" 지시를 합성(`RagPrompt`와 같은 방식, 포트 변경 없음).
5. **예산·재시도**: 분류는 LLM 단계 예산(`llmBudgetMs`, 50초)에서 `min(timeout-ms, 남은 예산)`만 쓴다. 시도마다 재분류하되 temperature 0이고 **시도별 라벨을 로그**에 남긴다(attempt_id). 분류 실패는 재시도 사유가 아니다. 복구 `reprocess`도 같은 규칙.
6. **로그/메트릭**: `classify_ms`, `request_kind`, `classify_fallback=reason`, `classify_slow`(분류 시간 > 2s, 콜드 로드 식별용), event_id·attempt_id.
7. **RAG off + 분류 on**: 허용(NEEDS_INFO만 의미 있음).

### 마일스톤 (M31~M36)
**미달 공통 규칙**(M30 선례): 어느 기준이든 미달이면 원인 단계(분류 / 검색 / LLM·콜드 로드)를 특정하고 개선안 또는 목표 변경안을 올린 뒤 **멈춤 → 사용자 결정**. 수행 완료와 합격을 구분해 기록하고 합격 항목만 PRD 체크.
- **M31 스파이크** (`docs/spikes/`, 코드 폐기): 임시 질문 20개(장애 8·단순 6·정보 부족 6, 이후 세트와 분리·폐기)로 3b·7b 라벨 신뢰도 1회, 3b+7b+bge-m3 동시 적재 `ollama ps`·메모리·콜드/웜 분류 시간 1회, 끝에 `ollama stop`. **멈춤**: 3b·7b 모두 전체 < 14/20 또는 장애 질문 재현율 < 7/8이면 사용자 결정. 3b만 미달이면 7b 분류기로 계속. 동시 적재 불가면 "분류 모델 = `llm.model`" 구성으로 계속(사용자에게 알림).
- **M32 데이터·라벨 정의·하니스** (프롬프트보다 먼저): 라벨 정의 문서(경계 규칙 포함), `docs/rag-eval/classification.json`(가상 질문, tuning 30 + final 60=장애 20·단순 20·정보 부족 20, 멘션 최상위 형태), 하니스 `scripts/classify-eval`(정확도·혼동 행렬·NEEDS_INFO 정밀도·주입 건수), **분류 off 주입 기준선**(검색만, LLM 없음) 측정. **판정 구성(2026-10-07 사용자 결정, M31 동시 적재 불가 반영)**: 분류 모델 = 답변 모델 `llm.model` = `qwen2.5:3b`(3단계 권장 기본과 같음, 3b+bge-m3 공존은 M31에서 확인). C2·C5·C8·e2e는 이 구성으로 판정한다. 3b가 C2를 못 넘으면 **멈춤 → 사용자 결정**(7b로 둘 다 올리면 답변 모델이 바뀌므로 자동 전환하지 않는다). **final은 커밋 해시로 고정**하고 프롬프트 튜닝에 쓰지 않는다. 3단계 final 멘션 15문항(C4)도 튜닝 금지로 표시. 기준선에서 "분류 off일 때 문서가 주입되는 단순+정보 부족 질문"이 15개 미만이면 C3을 **합격선에서 빼고 보고 항목으로 내린다**(M32에서 확정, 이후 변경 금지). 완료: 가짜 분류기로 경계(C2 51/60·18/20·NEEDS_INFO 오판 2건, C3 기준선 대비 90%) 검증.
- **M33 계약·분류 어댑터**: 먼저 `OpenAiCompatibleLlmClient`의 전송부(마감 취소 타이머 `execute`, `verifyModelExists(String)`)를 **패키지 전용 전송 클래스로 추출**(3단계 어댑터 회귀 위험 — 기존 `LlmClient` 단위 테스트 무변경이 완료 조건; 마감 취소 로직 복제 금지). 이어 `RequestClassifier` 포트·`RequestKind`, `ClassificationProperties`, `adapter/llm` 분류기(프롬프트는 **tuning 세트로만** 조정, 기동 시 모델 검증, 워밍업·keep_alive), `ClassificationStartupCheck`, `isAlert()`. 착수 전 `src/` 전체(main·test)의 iCloud 중복 파일("X 2.java")이 로컬 빌드에 컴파일되지 않는지 확인하고, 있으면 사용자에게 알린 뒤 정리한다(삭제는 사용자 확인 후). 완료: ArchitectureTest 무변경 통과, 어댑터 단위(파싱 표, 잡음·빈 출력·타임아웃·예외 → Failed) + 오류 유도(없는 모델 ID로 기동 거부) + tuning 정확도 기록.
- **M34 핸들러 분기·단위**: 분기 삽입(검색 호출 앞), 되묻기 지시, 알람·스레드 생략, 로그, 분류 off 회귀 0. 완료: 단위(3갈래×푸터·검색 호출 여부, 알람·스레드 생략, 시도별 라벨 로그, 재시도 시 재분류, fail-open) + `FullStackWiringIT`류 배선 회귀.
- **M35 외부 왕복·오류 유도**: 호스트 bootRun에서 최상위 멘션 3종 각 1건, 되묻기→스레드 답변→근거 답변 왕복 1건, 분류 모델 단절·무효 출력·타임아웃 유도 후 장애 질문 경로 폴백 확인, 확인 뒤 즉시 내림(M16·M28 선례).
- **M36 통합 실측·판정**(1회, 장비·Ollama 버전 기록은 M30과 동일 형식): 판정 구성 = 3b 분류 + 3b 답변(위 결정). **참고 측정**(판정 아님): 3b 분류 + 7b 답변(분리 구성)을 같은 질문 세트로 한 번 재서 M31에서 본 축출·재적재 지연이 실제 요청 e2e에 얼마나 더하는지 기록한다("분리가 이 장비에서 왜 성립하지 않는가"의 근거). 측정: 분류 하니스 final 60, 분류 on/off 주입 건수, Slack 왕복 15문항(장애 5·단순 5·정보 부족 5) 종류별 e2e, 웜·콜드 분류 시간과 폴백률, `ollama ps` 상주·시스템 메모리 압박·축출. ARCHITECTURE(ADR-10)·PRD 체크·EXPERIMENT-LOG·PLAN 갱신.

### 합격선(M32 시점에 고정, 이후 변경 금지)
- **C1 off 회귀 0**: `classification.enabled=false`에서 전체 빌드·기존 테스트 통과, 핸들러 동작 변경 없음.
- **C2 정확도(final 60)**: `Failed`(폴백)는 **오답으로 센다**(TROUBLE로 처리돼 재현율에 공짜 점수가 되지 않게; C5 폴백 건수와는 별도로 기록). 전체 ≥ 51/60 **그리고** 장애 질문 재현율 ≥ 18/20 **그리고** 장애·단순 40문항 중 NEEDS_INFO 오판 ≤ 2. 분류 후보 3b 우선; 3b 미달·7b 통과면 7b; 둘 다 미달이면 멈춤. 불확실성: 51/60의 Wilson 95% 구간 ≈ 74~92%, 18/20 ≈ 70~97% — 기능 검증용 관문이며 품질 보장이 아니다.
- **C3 주입 흡수**(M32에서 기준선 ≥15문항일 때만 합격선): 분류 off에서 문서가 주입된 단순+정보 부족 질문 중 ≥ 90%가 분류 on에서 주입 0. 장애 질문 **내부**의 무관 문서 1건 평균 주입(§31.1)은 이 단계가 해결하지 못함을 결과에 명시한다.
- **C4 3단계 회귀**: 3단계 final 멘션 15문항 중 ≥ 14가 TROUBLE. `scripts/rag-eval`(검색 불변) hit@3 ≥ 20/24, 근거 미주입 ≥ 5/6 유지 — 후자는 분류가 검색을 건드리지 않음을 확인하는 **회귀 점검**이며 실패 가능성이 낮다.
- **C5 지연**(위 판정 구성): **웜의 정의 = 워밍업 호출 1회 뒤 final 60개를 모두 센다(사후 제외 없음)**. 분류 p95 ≤ 2초(nearest-rank, 2초 초과 호출도 p95에 포함되고 `classify_slow`로도 기록) **그리고** 폴백 ≤ 3/60. 콜드 첫 호출 시간과 5초 초과 폴백 여부는 별도 기록(keep-alive 30m 설정 하에서 유휴 후 첫 질문 포함). 정상 답변 e2e p95 ≤ 45초(n=15 nearest-rank라 최댓값과 같음을 명시, 종류별 n=5 e2e는 **보고만** 하고 판정에 쓰지 않는다). 웜 측정 중 판정 구성(qwen2.5:7b + bge-m3 상주)을 실제로 적재한 상태에서 돌리고 시작·끝에 `ollama ps`를 기록한다(분류기 단독 측정 금지).
- **C6 fail-open**: 분류 모델 단절·무효 출력·타임아웃 각 유도 시 장애 질문 흐름으로 답하고 안내·푸터가 3단계와 같으며 로그에 사유와 시도별 라벨이 남는다.
- **C7 되묻기**(오분류는 C2에서 이미 센다 — 이중 계산하지 않는다): M36 Slack 왕복에서 `request_kind=NEEDS_INFO` 이벤트가 **3건 미만이면 C7 미합격**(검사 대상이 없는 합격 금지). 있으면 그 이벤트 전부에서 답변 끝이 `?`이고 검색 호출·푸터 없음(규칙 검사). 되묻기→스레드 답변 왕복 1건(스레드 답변은 봇 멘션이 있어야 이벤트가 생긴다)이 TROUBLE 경로로 답변 생성(검색 관련도는 보고만 하며 약한 질의는 "질의 재작성" 후속으로 기록).
- **C8 자원**: 판정 구성에서 동시 적재가 모델 축출로 LLM 50초 초과를 만들지 않는다. 만들면(3b+bge-m3가 축출되는 경우) 원인(메모리 압박·다른 프로세스)을 기록하고 멈춤 → 사용자 결정. 판정 구성은 단일 채팅 모델이므로 채팅 모델 간 축출은 구조적으로 없고, 임베딩과의 공존만 확인한다.
- **C9 구조**: `ArchitectureTest` 무변경 통과(분류 설정은 어댑터 전용). ARCHITECTURE·PRD·EXPERIMENT-LOG를 같은 작업에서 갱신.

### 수락 기준 ↔ 마일스톤
| 기준 | 마일스톤 |
|---|---|
| C1 | M33, M34 |
| C2·C3(기준선)·C4 | M32(하니스·기준선), M36 |
| C5·C8 | M36 |
| C6 | M33(단위), M35(유도) |
| C7 | M34(단위), M35(왕복), M36 |
| C9 | M33, M36 |

### 리스크
3b 라벨 신뢰도(M31 멈춤) · 라벨 경계 모호(정의 문서·tuning) · 콜드 로드로 분류가 사실상 꺼짐(keep-alive·C5 콜드 기록) · 3모델 동시 적재 축출(C8, 동일 모델 구성으로 완화) · 분류 세트 소규모(기능 검증용) · 장애 질문 내부 무관 문서는 못 줄임 · 프롬프트 주입으로 RAG 생략(영향 작음) · 스레드 후속은 분류하지 않으므로 단순 질문에도 검색이 돌 수 있음(수용, 재시도 시 라벨 비결정은 temperature 0·로그로 완화).

### 변경 요약(v1→v2)
분류 전용 포트(두 번째 `LlmClient` 폐기) · `isAlert()` 명시·스레드 전부 생략 · 데이터·기준선을 프롬프트 앞(M32)으로, final 해시 고정 · C3 기준선 조건 · C5 실질 한계(웜 p95 2s·폴백률) · 콜드/keep-alive · NEEDS_INFO 정밀도·M31 멈춤선 상향 · 미달 공통 규칙 · 판정 구성 = C2 선택 구성(A·B·C는 참고) · M33 분할(M34 단위, M35 왕복) · 대안 D 기각 근거 · Wilson 구간.

### 변경 요약(v2→v3)
`Optional<RequestClassifier>`+조건부 설정 배선(ArchitectureTest 무변경) · 전송부 추출·ECHO 조합 거부·워밍업 마감·keep-alive 상속 · `isAlert()` 단일 정의 · 판정 구성을 M32에 고정(답변 qwen2.5:7b) 및 C8 대체 절차 · C5 웜 정의(사후 제외 없음) · C7을 NEEDS_INFO 로그 이벤트로 한정 · C4 rag-eval은 회귀 점검임을 명시.

---

# 3단계(RAG) 계획

승인: 2026-10-04 사용자 요청으로 단계 전환. 근거 스펙은 인터뷰 결과(PRD §6 "3단계(RAG) 범위 확정")이고 합의 이력은 Planner → Architect → Critic 1차, Codex(gpt-6.1-sol) Architect → Critic 2차(Critic REVISE → 아래 "v4 보완" 반영, v4 재리뷰 없음). 각 마일스톤 완료 = 정상 흐름 외부 왕복 **및** 실패 흐름 오류 유도, 코드 리뷰 1회 이상. 무거운 Docker·Ollama는 마일스톤 끝 1회만 켜고 즉시 내린다.

## RALPLAN-DR
Principles: (1) RAG는 부가 — 꺼도·실패해도 기존 흐름 불변 (2) 조용한 실패 금지 (3) 상시 프로세스·자원 최소 (4) 코어는 포트만 안다, Slack 지식(이스케이프)은 어댑터 (5) 합격선 사전 고정.
Drivers: ① 노트북 자원 ② 60초 예산 불변 ③ 유출 안전 기본값.
Options: A 수평 분할+스파이크(선택) / B 얇은 end-to-end 먼저(기각: 주입 코드 전반이 폐기 후보, harness 없이 판정 불가). B의 장점(미지수 조기 확인)은 M24.5로 흡수.
ADR: Decision — A, pgvector 이미지를 RAG off에서도 사용. Drivers — 위 3개. Alternatives — B; 조건부 마이그레이션(Flyway 이력 분기·테스트 이중화로 기각). Consequences — 이미지 교체·테스트 5개 이전·기존 볼륨 초기화 안내(alpine→glibc collation). Follow-ups — 마스킹, 문서 URL 링크, 질의 재작성.

## 마일스톤
각 완료 = 외부 왕복 또는 오류 유도 + 리뷰 1회 이상. 무거운 Docker·Ollama는 M24.5 스파이크 끝, M28 확인, M30에서만 짧게 켜고 즉시 내린다(`ollama stop`·compose down 명시).

- **M24.5a 스파이크** (`docs/spikes/`, M9 선례, 코드 폐기): Ollama `/v1/embeddings` 지원, bge-m3 차원, 콜드/웜 임베딩 시간 1회. 끝에 `ollama stop`. 미지원이면 **멈춤 → 사용자 결정**.
- **M24.5b 계약 확정** (별도 PR): 포트 재설계 `VectorStore`(replaceDocument·search→Hit(docId,title,text,score)·deleteMissing·meta), `EmbeddingClient` 결과 타입, `DocumentSource`, 코어 `ReferenceList`. 평가 데이터(문서 20·질문 30, 튜닝/최종 분리)는 M29로 이동(데이터는 하니스와 함께 검증). 완료: 컴파일·ArchitectureTest.
- **M25 pgvector 기반**: compose 이미지 교체, 테스트 5개 `pgvector/pgvector:pg16` 이전, V3(documents, chunks `vector` 무인덱스 전수 검색, index_meta), Postgres `VectorStore` 어댑터. 볼륨 초기화 안내는 **멈춤 지점**. 완료: 전체 build 회귀 0, 문서 단위 교체 원자성·차원 불일치 거부.
- **M26 임베딩 어댑터·스위치·기동 검사**: 어댑터, `RagProperties`, 호스트 파싱 검사(`StartupInvariants`), index_meta 불일치 검사(Postgres 어댑터 기동 검사, RAG on·WORKER/ALL), `AppRole.INDEXER`, ArchitectureTest 허용 목록·ARCHITECTURE 갱신. 완료(오류 유도): ① `localhost.evil.com` 거부 ② RAG on + 외부 URL 거부 ③ `allow-external-rag` 켜면 기동 + 경고 로그 한 줄(문서 내용 없음) ④ 모델/차원 불일치 기동 거부 ⑤ RAG off면 검사 생략.
- **M27 색인 파이프라인**: 로컬 마크다운 어댑터, 청커, 코어 `IndexingService`(증분 키, 삭제 임계 비율 초기값 50% 초과 시 중단, 부분 성공·종료 코드, advisory lock), `scripts/rag-index`, 전체 재색인 옵션. 완료: 추가·수정·삭제 반영, 임베딩 단절 시 실패 목록+비0 종료 코드, 색인 중 kill 후 검색 무결, 동시 실행 차단.
- **M28 검색 주입·출처·폴백**: 코어 `RetrievalService`를 핸들러 L93~L95 사이 호출. 검색 예산 `min(5000, llmBudgetMs(t0))`, 시간 제한은 어댑터 `RestClient` 타임아웃 + 마감 비교 이중 강제, 마감 뒤 도착 결과 폐기 테스트. 데이터 블록 주입. 코어는 `ReferenceList`만 생성, 렌더링·이스케이프는 `ChatNotifier` 어댑터(포트 시그니처 변경). **재시도 규칙(확정)**: 검색 실패는 재시도 사유가 아니며 RetryRequested로 가지 않는다. LLM 재시도 시도에서는 검색을 **재실행**한다(검색 결과를 상태 저장소에 저장하지 않아 스키마 변경을 피함; 시도마다 마감이 독립이라 예산 이중 소모 아님). 관련 문서 없음 안내, 임계값은 초기값(M30에서 튜닝 세트로만 조정). 
  완료: 단위(참고 문서 규칙 — 중복 제거·제목 멘션 이스케이프·경로/원문 미노출·0건 생략·폴백 시 목록 생략, 시간 초과·실패 주입, RAG off 회귀 0, 알람·멘션 두 경로가 같은 주입 지점을 탐) **+ 외부 왕복**: 호스트 bootRun에서 실제 멘션 1건을 RAG off/on 각각, 임베딩 단절 유도 후 폴백 안내 문구 실제 부착 확인, 확인 뒤 즉시 내림(M16 선례).
- **M29 평가 데이터·하니스**: 가상 문서 20·질문 30(알람형 15+멘션형 15, 정답 없음 6, 튜닝/최종 분리) 커밋, 하니스(CLI/gradle 태스크로 실제 임베딩에도 실행 가능)가 hit@3·무근거 비율 계산(출처 형식 검사는 M29 점검 뒤 삭제 — 형식은 `SlackFooterRendererTest`가 고정), 답변 쌍(RAG on/off) 기록 형식(P2-4)과 "근거 활용·부족 안내" 점검 시트. 완료: 가짜 임베딩으로 경계(20/24, 19/24, 5/6) 검증.
- **M30 통합 실측·판정** (1회): bge-m3 vs nomic, qwen2.5 3b vs 7b(컨테이너 메모리 상한 적용), 검색 5초(모델 내려감/적재), 동시 적재(`OLLAMA_MAX_LOADED_MODELS`)와 채팅 모델 축출 영향, Slack 외부 왕복(알람형·멘션형 각 on/off), 오류 유도 재확인, 답변 쌍 기록·근거 활용 점검.
  **판정 규칙**: 합격선 = hit@3 ≥ 20/24, 정답 없음 ≥ 5/6, 정상 답변 p95 ≤ 45초, 한자·가나 혼용으로 최종 실패 안내가 된 최종 질문 0건(초기값, 착수 시 사용자 확인). 기본 모델 = 합격선 통과 모델 중 더 가벼운 쪽. **미달 시(hit@3 미달·두 모델 모두 p95 초과·축출로 LLM이 50초 초과)**: 원인 단계(검색/주입/LLM 콜드)를 특정하고 개선안 또는 목표 변경안을 올린 뒤 **멈춤 → 사용자 결정**(M9 선례). 수행 완료와 합격을 구분해 기록, 합격 항목만 PRD 체크. ARCHITECTURE·EXPERIMENT-LOG·PLAN 갱신.

## 스펙 수락 기준 ↔ 마일스톤
| 수락 기준 | 마일스톤 |
|---|---|
| RAG off 회귀 0 | M25, M28 |
| 문서 추가·수정·삭제 반영 | M27 |
| 임베딩 단절: 색인 실패 목록 / 질의 폴백 | M27 / M28 |
| 색인 중 kill 무결 | M27 |
| 모델·차원 불일치 기동 거부 | M26 |
| 외부 URL 기동 거부, 허용 시 기동+경고 | M26 |
| 평가 합격선 + 3B·7B 기록 | M29, M30 |
| 참고 문서 규칙, 경로·원문 미노출 | M28 |
| 검색 5초 상한·공통 마감 | M28, M30(적재 전/후) |
| Slack 외부 왕복 on/off 비교 | M28(1건), M30 |
| ArchitectureTest 유지 | M24.5b, M26 |

## 리스크
Ollama 임베딩 미지원(M24.5a에서 판명, 멈춤) · 볼륨 collation(멈춤) · 5초 vs 콜드 적재(폴백+측정) · 모델 축출로 LLM 지연(M30 판정 규칙) · 임계값 선후(초기값+M30 조정) · 평가 세트 소규모(기능 검증·회귀용, 일반 품질 보장 아님).

## 변경 이력(v2→v3)
M28 외부 왕복 추가, 수락 기준 표 추가, 재시도 규칙 확정, M30 불합격 경로·멈춤 추가, 스파이크/계약 PR 분리, 5초 강제 방식 명시, 하니스 실행 방식 명시, 단계 전환 게이트를 선두로 이동.

## v4 보완 (Codex Architect·Critic 2차 반영)
- **마감 계산(M28)**: 검색 마감 = `min(검색시작+5초, t0+50초, 총마감−Slack예산)`. `RestClient` 타임아웃만으로는 DB 연결 대기·SQL 실행을 못 막으므로 JDBC 연결·쿼리 타임아웃과 취소를 `VectorStore` 포트 계약에 포함(검색 기한·실패 결과 타입). 오류 유도: 검색 지연 주입 후에도 LLM·실패 안내 포함 총 60초·Slack 10초 유지 확인.
- **재시도 규칙 확장(M28)**: 재처리는 LLM 실패뿐 아니라 발신의 명확한 재시도 가능 실패에도 일어난다 — 두 경우 모두 검색을 재실행하고, `Unknown`에서는 재검색·재발신이 없음을 테스트로 검증.
- **경계(M24.5b/M27)**: advisory lock은 포트(예: `IndexLock`)로 두고 JDBC 수명은 Postgres 어댑터가 맡는다. DTO(`ReferenceList` 등)는 `core.model`. ArchitectureTest에 pgvector 클라이언트 패키지 금지 추가. 점수는 코사인 거리→유사도 변환·범위를 포트 문서에 명시.
- **순서 의존(M25/M26/M27)**: 차원 불일치 거부는 M26 설정에 의존하므로 M25는 어댑터 단위 거부까지만, 기동 거부는 M26. 빈 색인 초기화 규칙(첫 색인이 index_meta 생성)과, 전체 재색인(INDEXER 역할)이 메타 불일치로 기동 거부되지 않는 별도 경로를 M26에 포함.
- **평가 분모(M29)**: **최종 질문 30개(정답 있음 24+없음 6)를 고정**하고 튜닝 질문은 별도 세트(예: 10개)로 추가한다. 문서는 한/영/혼합을 포함. 모델별 메모리·언어 방어 재시도 수·혼용률을 M30 기록 항목에 명시.
- **완료 조건 정리(규칙 5)**: 공통 문구를 "정상 흐름 외부 왕복 **및** 실패 흐름 오류 유도"로 정정. M24.5b·M29는 계약·데이터 작업이므로 "컴파일·단위 검증"으로 범위를 구분 명시하되, M25~27은 각 마일스톤 끝에 실제 Postgres(Testcontainers) + 실제 Ollama 임베딩 1회 통합 확인을 두고 오류별 기대 결과를 표로 적는다(자원 정책: 마일스톤 끝 1회, 직후 내림).
- **자원 정책**: 컨테이너 메모리 상한은 호스트 Ollama를 제한하지 못한다. 제한 대상은 앱·Postgres·RabbitMQ 컨테이너이고, Ollama는 `OLLAMA_MAX_LOADED_MODELS`·`keep_alive`·종료 절차(`ollama stop`)와 활동 모니터/`ollama ps` 측정으로 관리한다고 정정.
- **안전·복구**: M26에 LLM/임베딩 외부 허용 플래그 독립 검증(한쪽만 외부) 추가. 볼륨 초기화 전 `pg_dump` 백업·복원 절차를 안내에 포함. M27 "검색 무결"은 "중단 전 문서가 유지되고 부분 청크가 검색에 노출되지 않음"으로 구체화.
- **대안 비교 보강**: B도 작은 하니스를 포함할 수 있다 — 기각 근거를 "하니스 불가"가 아니라 변경량(주입 코드 전반이 포트 확정 전 임시)·자원 비용(실측 반복)·이미지 교체 운영 비용 대비로 정정. 스틸맨은 M24.5a(+소수 문서 임베딩→SQL 검색→채팅 폐기용 수직 실험 1회)로 흡수.
- **ADR 긴장**: pgvector 이미지는 RAG off 사용자에게도 확장 설치를 요구해 ADR-9 "꺼도 Vector DB 없이 동작"과 해석상 충돌한다 → ADR-9에 "Vector DB 없이"는 기능 의미이고 인프라는 pgvector 이미지를 공통 사용한다고 주석 추가 필요(PLAN 반영 시).
- 미정 용어는 착수 시 사용자 확인: 삭제 임계 비율 50%, 튜닝 질문 수, 언어 위반 합격 기준 0건.

---

# 2단계(P1) 계획

## 1. 요구사항 요약

수신 서버는 서명 검증·필터링 후 이벤트를 **큐에 저장하고 저장 확인을 받은 뒤에만** 200을 준다(P1-1).
워커가 큐를 소비해 공유 상태 저장소에서 처리 권한을 선점하고, 기존 `SlackEventHandler`로 LLM→답글을 수행한다(P1-2).
상태는 프로세스 밖(Redis)에 두어 재시작·재전달·다중 워커에서도 최종 답글이 1개다(P1-3·P1-8).
일시 실패는 최대 3회 재시도하고, 최종 안내가 실패하면 DLQ로 보낸다. 전송 결과 불명은 자동 재발신 없이 복구 대상으로 남긴다(P1-4, 규칙 11).
"확인 중" 표시(P1-5)와 스레드 문맥(P1-6)을 더하고, 처리 시간·적체·실패율을 로그로 본다(P1-7).
최종 산출물은 PRD §5의 **고정 환경 성능 검증(순차 20건·버스트 5건×2)·중복 검증·복구 검증**이다.

## 2. 수락 기준 (테스트 가능 형태)

| # | 기준 | 판정 방법 |
|---|---|---|
| B1 | 유효한 새 이벤트는 `XADD` 후 `WAITAOF`가 로컬 fsync 1을 반환한 뒤에만 200. 저장 실패·확인 시간 초과·반환값 불일치는 503 | Redis 중단·타임아웃 유도 curl → 503. 정상 시 `enqueued` 로그 후 200 |
| B2 | 수신 역할에서는 핸들러·워커·`LlmClient`·`SlackClient` 빈이 생성되지 않고 LLM·Slack 호출이 없다 | 역할별 컨텍스트 테스트 + 로그 |
| B3 | 큐 저장 후 수신 서버를 `kill -9`해도 이벤트가 워커에서 처리된다 | 복구 실험 R1 |
| B4 | 워커가 처리 중일 때 `kill -9`하면, 임대 만료 후 재처리되어 최종 답글이 1개다 | 복구 실험 R2 |
| B5 | Slack 발신 성공 직후·완료 기록 전 중단 → ACK되지 않은 메시지를 `XAUTOCLAIM`이 회수하고, 결과표에 따라 입력 보존 → 만료 `SENDING`→`UNKNOWN` 전이 → ACK가 `finalize` 순서대로 수행됨. 자동 재발신 0회. `recovery check`→`resolve`로 해결 | 복구 실험 R3 (`experiment.halt-after-send`) |
| B6 | 동일 event_id 10회 동시 전달 → 최종 답글 1개. 완료 후 재전달 → 답글 추가 0개 | 중복 실험 D1·D2 |
| B7 | 서로 다른 event_id 10건 → 답글 정확히 10개 | 버스트 5건×2와 겸함(D3) |
| B8 | 재시도 가능 오류(LLM 연결·5xx·기한 초과, 미전송이 확실한 Slack 일시 실패)는 최초 1회 + 재시도 3회(5s·30s·120s), 소진 시 최종 안내 1회. 영구 오류는 재시도 0회, 결과 불명은 재발신 0회 | §3 M13 전이표의 각 행을 유도하고 로그의 `gen` 확인 |
| B9 | 최종 안내가 명확히 실패하면 `DEAD`+DLQ, 결과가 불명이면 `UNKNOWN`+복구 목록. `finalize`·재시도 예약·재투입은 **멱등 보존/투입 → 상태 → 삭제·ACK** 순서라, 어느 명령 사이에서 오류·중단이 나도 입력이 보존된 채 종료된다(Lua는 롤백하지 않으므로 순서로 보장) | `slack.base-url` 스텁 유도 + 테스트 전용 fault 인자(`fail_after=N`, 운영 빈에서는 비활성)로 각 쓰기 명령 뒤 실패 주입·스크립트 전후 kill 뒤 재전달 통합 테스트 |
| B10 | 상태·재시도 예약·DLQ·복구 기록의 저장이 실패하거나 거절되면 XACK하지 않는다(메시지는 pending에 남음) | 상태 저장소 오류 주입 통합 테스트 |
| B11 | 최초 수신 후 24시간이 지난 미완료 이벤트는 자동 실행되지 않고 복구 대상이 된다(판정은 Redis 시각). 사람이 `reprocess`로 승인하면 **승인한 gen의 첫 선점에서 승인이 소비되어 정확히 1회만** 실행되고, 그 시도가 실패·소실되면 **24시간 이내·초과와 무관하게** 새 승인 전까지 복구 대상으로 돌아간다 | 시각 주입 통합 테스트(자동 차단, 수동 승인 성공, 24시간 이내·초과 각각에서 승인 실행 중 워커 중단 후 새 승인 없는 실행 0회, 승인 실행의 일시 실패) |
| B12 | 멘션 **최초 수신부터** "확인 중" 표시까지 p95 ≤ 3초. LLM 워커 적체·빠른 답변 처리 어느 쪽에서도 누락 0, 표시 실패해도 답변 흐름은 계속 | 실제 멘션 + 워커 정지(적체) + 반응 소비자 지연(답변 먼저 끝남) + `reactions.add` 오류 유도 |
| B13 | 스레드 안에서 되물으면 이전 대화(최대 N개·M자)가 프롬프트에 들어간다. 조회에 실패하면 문맥 없이 진행하고 로그를 남긴다 | 실제 스레드 2턴 대화 + 조회 실패 유도 |
| B14 | 처리 시간(수신·큐 저장·큐 대기·LLM·발신·답변), 큐 적체(`XLEN`·pending·재시도 ZSET·DLQ·복구 목록), 실패율을 로그 grep으로 집계할 수 있다 | `EXPERIMENT-LOG.md` 집계 절차 실행 |
| B15 | 워커 2개(`docker compose --scale worker=2`)를 동시에 돌려도 B6·B7이 성립한다 | 다중 워커 실험 |
| B16 | PRD §5 고정 환경: 순차 20건에서 수신 p95 ≤ 200ms·정상 답변 p95 ≤ 45s, 버스트 5건×2에서 수신 p95 ≤ 200ms·기한 초과 0, 둘 다 유실·실패·중복 0 | 성능 실험 P, `ceil(0.95×N)` |
| B17 | 코어에 HTTP 타입 import가 없다(규칙 2, A13 유지. M19부터 `event/`가 `core/`로 바뀌었고 `ArchitectureTest`가 강제) | `grep -rE "jakarta\.servlet|org\.springframework\.http" src/main/java/com/slack/lab/core/` 0건 |
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
- **P(성능)**: M9 고정 환경(워커 LLM 동시성 1)에서 워밍업 후 (a) 순차 20건 (b) 서로 다른 5건 동시 × 2배치를 실행한다. 배치가 모두 끝난 뒤 다음 배치를 시작한다. LLM 호출은 총 30회 남짓이라 노트북 부하를 제한한다.
  - (a)는 수신 p95·답변 p95, (b)는 수신 p95·기한 초과 0, 둘 다 유실·실패·중복 0으로 B16을 판정한다. (b)의 답변 시간은 적체 관측값으로만 기록한다.
  - 실패 안내·누락·429로 인한 실패는 정상 답변으로 세지 않고 불합격 사유로 기록한다.
  - 콜드 스타트는 따로 기록한다.
  - 환경 표에 빠진 값이 있으면 측정을 시작하지 않는다.
- **D(중복)**: D1 동일 event_id 10회 동시 전달(LLM 1회), D2 완료 후 재전달, D3 서로 다른 10건(P의 버스트 5건×2 결과로 판정). 워커 1개와 2개에서 각각 실행해 B6·B7을 판정한다.
- **R(복구)**: R1 큐 저장 후 수신 컨테이너 `docker kill -s KILL`, R2 처리 중 워커 컨테이너 kill, R3 발신 직후 halt로 B3·B4·B5를 판정한다. 성능 측정과 분리하고, ngrok은 켜 둔 채로 진행한다.
- 결과는 `EXPERIMENT-LOG.md`에 남긴다. **"검증 수행 완료"와 "P1 합격"을 구분해 적는다.** 합격한 항목만 PRD §6 2단계 체크박스에 체크한다. 미달이면 원인 단계(큐 대기·LLM·발신·429)를 특정하고 개선안 또는 목표 변경안을 사용자에게 올린다.
- **완료**: 세 실험을 모두 수행하고 판정을 기록한다. 2단계 완료는 합격 이후에만 가능하다.

### 2단계 후속 — 기반 전환 (ADR-9, 2026-10-02)

3단계(RAG) 전에 기반을 먼저 바꾼다. 3~5단계가 Redis 구현 위에 쌓이면 교체 비용이 커진다. 프로토콜과 실험 설계는 승계하고, Redis/Lua 구현만 어댑터로 교체한다. 각 마일스톤은 기존과 같은 규칙(완료 = 외부 왕복 + 오류 유도, 리뷰 1회 이상)을 따른다.

- [x] **M19 포트/어댑터 분리 (DIP)** — 완료 (`EXPERIMENT-LOG.md` §16): 코어 포트 정의 — 메시지 큐(발행/소비), 작업 상태 저장소(선점·임대·종료·미해결 목록), LLM, 임베딩, 벡터 저장소, 채팅 알림, 알람 입력. 현재 Redis·Slack 구현을 어댑터 패키지로 옮기되 동작은 그대로 둔다. 코어→어댑터 import 금지 아키텍처 테스트 추가. **완료**: 기존 테스트 전부 통과 + 새 테스트로 코어가 어댑터를 모름을 확인.
- [x] **M20 Postgres 작업 상태 어댑터** — 완료 (`EXPERIMENT-LOG.md` §18, 선점 지연 p95 1ms) (M21 전까지 운영에 배선하지 않고 테스트 전용 — 재시도 재투입은 M21에서 코어가 큐 포트의 지연 발행으로 한다): 선점 결과표를 조건부 UPDATE/INSERT ON CONFLICT로 이식, 임대 갱신, 7일 보존 정리, 미해결 목록 조회. Testcontainers로 결과표 각 행, 동시 N 선점 1승, 부분 실패 후 재실행, 24시간 창, 수동 승인(1회 소비)을 다시 검증. **선점 지연 p95를 측정해 기록**한다(수신 경로 밖이어도 기록).
- [x] **M21 RabbitMQ 큐 어댑터** — 완료 (`EXPERIMENT-LOG.md` §19, 발행 확인 p95 16ms. 재시도는 TTL 큐가 아니라 Postgres 발신함+릴레이) (`QueueDelivery.defer()`를 지연 재발행으로 구현, 재시도 예약·재투입을 `ProcessingStateStore.scheduleRetry`에서 큐 포트의 지연 발행으로 이전): publisher confirm 뒤 200, durable quorum queue, 수동 ack, 전달 횟수 제한 + DLQ, 지연 재시도(TTL 큐 또는 지연 플러그인 중 하나를 측정해 선택), prefetch 1과 소비자 타임아웃을 LLM 처리 시간(최대 50초)에 맞춤. **완료**: 수신 kill·워커 kill에서 유실 0, 재전달이 Slack 재발신으로 이어지지 않음.
- [x] **M22 Redis 제거와 복구 CLI 전환** — 완료(PR #39 배선, M22-2 제거, `EXPERIMENT-LOG.md` §20·§21):: compose를 RabbitMQ + Postgres로 교체, 복구 CLI(list/check/resolve/reprocess/close)를 Postgres 목록 기반으로, 결과 불명 시 스레드 읽기 전용 자동 조회(발견 시 완료 처리, 미발견은 결과 불명 유지, 자동 재발신 없음). residue-check를 새 저장소에 맞게 재작성. P·D·R 실험을 다시 수행해 B16 재판정.
- [x] **M23 알람 입력 어댑터** — 완료(`EXPERIMENT-LOG.md` §22):: 전용 엔드포인트(시크릿 인증), CloudWatch(SNS) 어댑터, "같은 알람" 키(장애 식별자 + 발생 회차)로 중복 억제, 해결 뒤 재발은 새 건. 장애 한 건에 대표 리포트 하나. 후속 멘션의 스레드 문맥은 기존 경로를 그대로 쓴다(알람 스레드에서의 실제 멘션은 미검증).
- [x] **M24 오류 모델 정리** — 완료(`EXPERIMENT-LOG.md` §23): `ErrorCode`·`ErrorInfo`·`ProcessingStage`·`MessageKind`·`Failure`로 문자열 reason/stage 제거, 문자열 파싱(`kindFromStage`) 삭제, DB는 stage·error_code·error_detail 분리 저장(로컬 DB는 초기화 전제, 호환 처리 없음). 예외 계층·Advice는 보류.
- 3단계 제약(착수 시 PLAN에 반영): LLM·임베딩 엔드포인트 스위치(기본 사내·로컬), RAG on/off(꺼도 동작), 벡터 저장은 같은 Postgres(pgvector).

## 4. 리스크와 대응

| 리스크 | 대응 |
|---|---|
| 기본 Ollama 설정에서 10 동시 추론 시 답변 p95 30초 미달 | **발생(M9)** → 사용자 결정으로 부하 조건 축소(순차 20·버스트 5×2), 워커 LLM 동시성 1 |
| `XADD` 반환만 보고 내구성이 있다고 착각 | `WAITAOF` numlocal=1 검사, `kill -9` 실증. 주장은 프로세스 크래시 범위로 한정 |
| fsync가 수신 p95 200ms를 잠식 | M9에서 `XADD+WAITAOF` p95를 재고 `enqueue-timeout-ms`를 정한다 |
| 완료 기록 없이 ACK해 `SENDING`이 고착 | 워커가 반환값을 확인한 뒤에만 ACK(B10). 미ACK 메시지는 XAUTOCLAIM이 회수해 결과표로 `UNKNOWN` 처리 |
| 상태 전이와 입력 보존 사이 중단으로 복구 입력 유실(Lua는 롤백 없음) | 보존 → 상태 → ACK 순서 + 멱등 보존 + 결과표 2'행, 명령 사이 오류 주입 테스트(B9) |
| 수동 승인이 재전달로 여러 번 실행 | 승인을 gen에 묶고 첫 선점에서 소비, `manual_run` 영속 표시, 소실 시 4'행으로 `DEAD`, 승인 실행은 `finalAttempt`(B11) |
| 재시도 재투입 중 실패로 입력 유실 | `XADD` 후 `ZREM`, 중복은 결과표로 억제 |
| 절전·GC로 임대가 만료된 뒤 성공 보고가 거절됨 | 같은 attempt의 `UNKNOWN→COMPLETED` 허용 |
| 재시도 중복 투입, 재전송(gen 0)과 예약이 경합 | 선점 결과표의 gen 비교, 스케줄러 Lua 원자 처리 |
| XAUTOCLAIM이 정상 처리 중인 메시지를 계속 회수 | `min-idle ≥ 총 기한 + 임대`를 불변식으로 검사(기본 100초). 대가로 죽은 워커 회수에 최대 약 100초가 걸린다(성능 측정과 분리) |
| Slack 429가 버스트 배치의 답변 시간을 오염 | M9에서 관측해 채널 분산 여부와 판정 규칙을 확정한다. 429 건수는 따로 기록한다 |
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

## 6. 사용자 결정 대기

| # | 결정 | 시점 |
|---|---|---|
| 1 | ~~이 계획 승인~~ → **승인(2026-09-28)** | 완료 |
| 2 | ~~1단계 완료~~ → **완료**(#17, `v0.1.0`) | 완료 |
| 3 | ~~2단계 Git 정책~~ → **1단계와 동일**(자동 PR·`develop` 병합), `AGENTS.md` 반영 | 완료 |
| 3' | ~~실행 환경~~ → **Docker Compose(Redis·앱) + 호스트 Ollama** | 완료 |
| 4 | **발생(2026-09-28)**: 10 동시 답변 p95 272.8s, 순차 버스트 p95 216.9s, 단건 p50 24.3s. 처리량(8.8 tok/s × 평균 191토큰)상 10건 버스트는 구조와 무관하게 약 217s가 걸린다. → **결정(2026-09-28)**: 노트북 부하를 줄이는 방향. 순차 20건(답변 p95 ≤ 45s) + 버스트 5건×2(정확성만) | 완료 |
| 5 | Slack 조작: 테스트 채널 추가(429 대응 시, M9), 스코프 재설치(M14 착수 전) | 해당 마일스톤 |

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
  2. 이모지 완료 교체와 대량 동시 부하 검증(원래 10×10·100건)은 P1 이후, 별도 장비가 생기면 한다.
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

---

# 부록: 1단계(P0) 계획 (완료, 기록 보존)

부록 안에서 쓰는 §번호는 부록의 절 번호를 뜻한다(예: "§7 참조"는 부록 7).

## 부록 1. 요구사항 요약

Slack `app_mention` → 서명 검증 → 중복 억제 → Ollama 답변 → 원 메시지 스레드에 답글. 전부 **동기**로 처리하고,
그 결과 생기는 **3초 초과·재전송**을 로그 숫자로 관찰해 `docs/EXPERIMENT-LOG.md`에 남긴다 (P0-7이 이 단계의 진짜 산출물).

## 부록 2. 수락 기준 (테스트 가능 형태)

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

## 부록 3. 구현 단계

각 단계의 "완료"는 빌드가 아니라 **외부 왕복 또는 오류 유도 확인**이다.

#### M0. 사전 준비 (코드 없음)
- **Ollama 설치**(현재 미설치) 후 모델을 받고 `ollama list`로 확정한 ID를 설정에 쓴다. 추측 금지. 기본 후보 `qwen2.5:7b`, 장비에 무거우면 `qwen2.5:3b`(ARCHITECTURE §4). **모델 ID·digest·양자화·Ollama 버전·장비 사양을 이 시점에 기록**해 A12를 조기 충족한다.
- Ollama 직접 호출 검증: `curl localhost:11434/v1/chat/completions` 로 응답 확인.
- Slack 앱 생성: 스코프 `app_mentions:read`, `chat:write`, 이벤트 구독 `app_mention`, 설치 후 Bot Token·Signing Secret 확보. 봇을 테스트 채널에 초대.
- `.env`(비커밋), `.env.example`(키 이름만) 커밋. `.gitignore`·`git init`·GitHub 저장소 생성은 완료(§6).
- **ngrok 장기 요청 실측**: 60초 지연 응답을 내는 임시 엔드포인트로 ngrok이 요청을 몇 초까지 유지하는지 1회 측정한다. 걸리면 처리 예산을 조정하고 PRD §5와 함께 갱신한다(엣지 504가 Slack 재전송으로 오인되면 P0-7 관측이 오염된다).
- **완료**: curl로 Ollama 실응답 확인, 토큰·시크릿 보유, ngrok 한계 측정값 기록.

#### M1. 프로젝트 골격
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

#### M1.5. 기한 강제 스파이크 (계획 최대 리스크를 앞당김, 타임박스 4시간)
- 필요한 것은 JVM과 스텁뿐이라 Ollama·Slack 없이 수행한다. 스텁 3종: JDK `com.sun.net.httpserver.HttpServer` 또는 `nc -l`로 (1) 헤더만 보내고 정지 (2) 본문 중간 절단 후 정지 (3) accept 후 무응답.
- 후보와 판정은 §7 참조. 성공 기준: 3종 모두에서 취소 후 **소켓이 실제로 닫히고** 남은 기한 안에 호출자가 실패를 받는다. `HttpRequest.timeout`이 헤더까지만 덮는지 본문까지 덮는지는 **단언하지 말고 이 스파이크의 1순위 측정 대상**으로 둔다.
- **Fallback**: 두 후보 모두 진행 중 요청을 끊지 못하면 소켓/스트림 강제 close를 최소 요구로 삼고, 그래도 안 되면 한계를 `EXPERIMENT-LOG.md`에 기록한다. 인터럽트만 보내고 방치하는 구현은 금지(ARCHITECTURE §4.1).
- 결론은 M4·M5 **양쪽 전송 계층에 동일하게 적용**한다(타임아웃 의미를 한 번만 학습).
- **완료**: 스텁 3종 결과표와 채택 방식이 `docs/EXPERIMENT-LOG.md`에 기록됨.

#### M2. 서명 검증 + 수신 컨트롤러 (P0-1·2)
- `SlackSignatureVerifier`: `v0:{ts}:{rawBody}` HMAC-SHA256, `MessageDigest.isEqual`, 5분 초과 거부.
- `SlackEventController`: `@RequestBody String`(raw) → 검증 → 그 후 파싱. 401/400/`url_verification` 분기 (ARCHITECTURE §3.1).
- 재전송 헤더 `X-Slack-Retry-Num`·`X-Slack-Retry-Reason`을 첫 줄에서 로그.
- 테스트: 검증기 단위(정상·변조·시간 초과·빈 시크릿), 컨트롤러 슬라이스(401/400/challenge).
- **완료**: ngrok URL을 Slack Request URL에 등록해 **Verified** 표시 (실제 Slack 서명으로 최초 검증 — 프로토타입에서 미확인이었던 항목).

#### M3. 값 객체 + 중복 억제 (P0-3·8)
- `SlackMessageEvent`: event_id, channel, user, text, ts, thread_ts, bot_id, subtype. `shouldIgnore()` 로 봇/subtype 차단. 답글 스레드 = `thread_ts ?? ts`. 프롬프트에서 `<@봇ID>` 멘션 토큰 제거.
- `EventDeduplicator`(인메모리): 원자적 선점(`ConcurrentHashMap.compute`), 상태 `PROCESSING/SENDING/COMPLETED/FAILED/UNKNOWN`, `attempt_id`.
  - 전이 API는 `transition(eventId, attemptId, from, to)` **CAS**로 고정한다. 소유자(`attempt_id`)가 아니면 거절한다.
  - `FAILED`→새 `PROCESSING` 재선점 시 새 `attempt_id`가 이전 소유자의 지연 쓰기를 무효화한다.
  - `COMPLETED`·`UNKNOWN` 10분 유지, **`FAILED`도 축출 규칙을 둔다**(맵 무한 증가 방지). 실행 중 엔트리는 TTL 청소 제외 (ARCHITECTURE §3.2). `get` 후 `put` 금지.
  - `experiment.dedup-enabled=false`이면 선점을 건너뛴다(M8의 중복 답글 관측용 실험 스위치, slow-mode와 같은 성격).
- 핸들러에 노출하는 것은 저장소가 아니라 **`AttemptHandle`**(`markSending/markCompleted/markFailed/markUnknown`)이다.
- 테스트: 동시 N회 선점 시 1승(A9), 소유자 아닌 전이 거부, TTL 경계, 청소가 PROCESSING을 지우지 않음, FAILED 재선점.
- **완료**: 단위 테스트 통과. (외부 왕복은 M6에서 확인)

#### M4. LLM 클라이언트 (P0-4)
- `LlmClient` 인터페이스(호출 시 **남은 기한**을 받음), `OpenAiCompatibleLlmClient`(`choices[0].message.content`, `max_tokens`, `keep_alive`, 시스템 프롬프트로 길이 제한), `EchoLlmClient`. 전환은 `llm.client`.
- 전송 계층은 **M1.5 결론을 따른다.** 연결 제한은 클라이언트 단위이므로 `llm.connect-timeout-ms=3000` 고정이다(호출별 `min(3s, 남은 시간)`은 표현하지 않는다).
- 기동 시 모델 존재 확인은 **fail-fast**(pitfall 8): 존재하지 않는 모델 ID면 원인이 분명한 오류로 기동 실패. 우회는 `llm.verify-model-on-startup=false`(A6 유도용). 워밍업 1회.
- **완료**: 실제 Ollama 호출 성공(응답·소요 시간 로그). 잘못된 모델 ID로 기동하면 실패.

#### M5. Slack 발신 클라이언트 (P0-5)
- `SlackClient.postMessage(channel, thread_ts, text, deadline)`; 본문 `ok` 확인, `ok:false` 오류 코드 분류. `slack.base-url` 사용.
- 결과 3분류: **성공(ts 확인) / 명확한 실패(전송 안 됨 확실) / 결과 불명**(요청 후 연결 끊김·읽기 타임아웃). 예외를 밖으로 던지지 않고 결과 타입으로 반환.
- 기한 `min(발신 시작+slack.send-deadline-ms, t0+processing.total-deadline-ms)`, 남은 시간 없으면 발신 시작 안 함(A15). 전송 계층은 M1.5 결론과 동일.
- **완료**: 실제 채널에 스레드 답글 1건, `ok:false` 유도(잘못된 채널) 시 실패로 분류되고 로그.

#### M6. 핸들러 + 흐름 조립 (P0-6, 전체 왕복)
- `SlackEventHandler`: HTTP 무지식. 입력 = 이벤트 + `AttemptHandle`(attempt_id, t0 포함), 출력 = 결과 타입(성공/명확한 실패/결과 불명). `markSending` 성공 후에만 발신, 기록 실패 시 발신 금지.
- 흐름: LLM(≤`llm.deadline-ms`) → 성공이면 답변, 실패·기한 초과면 **실패 안내 1회** → `COMPLETED/FAILED/UNKNOWN`. 답변 발신 실패 뒤 안내 연쇄 발신 금지. 늦은 LLM 결과 폐기.
- 컨트롤러: 결과 → HTTP 응답 매핑 (ARCHITECTURE §3.1 표), 처리 권한 확보 후 예외는 200 유지·로그, 선점 전 내부 오류만 500.
- **응답 쓰기 실패**(3초 뒤 Slack이 이미 연결을 끊은 경우 등)는 `ack_delivered=false`로 로그에만 남기고 **dedup 상태를 바꾸지 않는다**(핸들러가 이미 상태를 확정한 뒤다).
- `experiment.slow-mode-ms` 지연 스위치: LLM 예산에 포함 (ADR-7).
- **`// 2단계에서 여기가 바뀐다` 주석만** 남기고 큐 코드는 넣지 않는다.
- **완료**: A1(실제 멘션 → 스레드 답글), A6·A8·A10·A15·A16 오류 유도 확인.

#### M7. 계측 (P0-7, 규칙 7)
- 로그 필드: event_id, attempt_id, 단계, 결과, 소요(단조 시계), 최초 수신 시각, 재전송 헤더, `ack_delivered`, 종류(답변/실패 안내). 프롬프트·본문·토큰·시크릿은 로그 금지.
- 집계할 값: 재전송 횟수 / 중복 억제 수 / 중복 답글 수 / LLM 소요 시간 (답변과 실패 안내 분리). 집계는 로그 grep 수준으로 충분(지표 라이브러리 도입 금지).

#### M8. 실험 + 문서 (P0-7 산출물)
1. 환경 기록 확인(M0에서 확보한 값, A12).
2. **경계 실험**: `llm.client=echo` 고정, `slow-mode` 2.5s / 3.0s / 5s / 30s 를 **각 5회 반복**. 재전송 발생 여부·횟수(관측값, 추정 금지)·중복 억제·중복 답글 수·`ack_delivered`. 실제 모델은 쓰지 않는다(예산 소진과 재전송 경계가 섞이지 않게).
3. **실제 LLM 실험**(별도 표): slow-mode 0, 실제 Ollama 지연으로 동일 관측. 콜드 스타트는 분리 기록.
4. **중복 답글 관측 배치**(1회): `experiment.dedup-enabled=false`로 실제 중복 답글을 만들어 dedup on/off 대비표를 남긴다("왜 큐가 필요한가"의 근거).
5. 선택 배치(1회): `llm.deadline-ms`·`processing.total-deadline-ms`를 늘려 50초 하드 캡이 가리는 현상(90초 뒤 답변 + 그사이 재전송) 관측.
6. 오류 유도 매트릭스: A2·A5·A6·A7·A8·A10·A15·A16.
7. **실험 체크리스트**: ngrok URL 재등록, 반복 타임아웃으로 Slack이 이벤트 구독을 자동 비활성화하지 않았는지 배치 사이 확인·재활성화, 배치 간 간격.
8. 갱신: `docs/EXPERIMENT-LOG.md` 작성(채널 ID·event_id 마스킹), `AGENTS.md` 현재 단계 줄, 구조가 바뀐 부분은 `ARCHITECTURE.md` (규칙 9). P0 실측으로 PRD §8 "7B로 충분한가"·P1 성능 목표 달성 가능성 판단.
- **완료**: PRD §6 1단계 체크박스 3개를 실측 데이터로 채움.

## 부록 4. 리스크와 대응

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

## 부록 5. 검증 요약

- 단위: 서명 검증기, dedup 상태 전이·동시성·CAS, `shouldIgnore`, 결과 분류.
- 슬라이스: 컨트롤러 401/400/challenge/무시/처리.
- 스파이크: 스텁 3종에서 취소 후 소켓 종료(M1.5).
- 외부 왕복(수동): Request URL Verified → 실제 멘션 → 스레드 답글.
- 오류 유도: 잘못된 서명, Ollama 중단, 런타임 잘못된 모델 ID, 지연·조각·정체 응답, `ok:false`, 발신 중 연결 끊김, 예산 소진.
- 관측: 재전송 헤더·중복 억제·중복 답글·LLM 시간·`ack_delivered` 로그 → 실험 기록.

## 부록 6. 확정된 결정

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

## 부록 7. RALPLAN-DR 요약 (short mode)

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

## 부록 8. ADR

- **Decision**: 1단계를 M0→M1→M1.5→M2…M8로 진행한다. 처리 예산 60초(LLM 50초·발신 10초)는 PRD §5 요구로 유지하되 설정값으로 노출한다. 호출 기한은 M1.5 스파이크로 A2/B 중 확정한다. 핸들러는 `AttemptHandle`만 받는다. 실험 배치에 예산·dedup 해제 배치를 추가한다.
- **Drivers**: 예산의 실제 강제 가능성 / 재전송·중복의 정확한 관측 / 2단계 이행 시 HTTP 무의존.
- **Alternatives considered**: `RestClient` 유지(A1, 호출별 기한 불가), Apache 기반(B, 의존성·워치독 비용), 인터럽트 방치(C, 금지), 컨트롤러 전이(Option 2, 불변식 위반), 콜백 주입(Option 3, 간접화 비용), 60초 예산 폐기(Architect antithesis — PRD §5 요구이자 문서화된 비기능 요건이라 유지하고 설정으로 해제 가능하게 함).
- **Why chosen**: 최대 리스크인 기한 강제를 M1.5로 앞당겨 후속 마일스톤의 전송 계층 결정을 미리 확정하고, 예산·dedup을 설정 스위치로 둬 코드 추가 없이 P0-7의 관측 충실도와 PRD의 상한을 함께 얻는다.
- **Consequences**: 스파이크에 4시간 타임박스가 생긴다. 단일 스레드 동기 흐름이라 60초는 호출별 기한의 합으로만 강제되며(전역 워치독 없음), 그 한계를 A16과 리스크 표에 명시했다. 실험 설정 키가 늘어난다(`llm.client`, `experiment.dedup-enabled` 등).
- **Follow-ups**: (1) M0에서 ngrok 60초 실측 후 예산 조정 여부 판단 (2) M8-5 결과로 50초 하드 캡이 학습 목표를 가리는지 보고 PRD §5 재검토 (3) P1 착수 전 `AttemptHandle`을 `ProcessingStateStore` 위로 이식 (4) 스파이크 결과표를 `EXPERIMENT-LOG.md`에 기록.

## 부록 9. Changelog (Consensus 1회전에서 반영한 개선안)

- **Critic BLOCKER**: A6 유도 방식을 (a) Ollama 중단 (b) 검증 우회 프로파일로 재정의, `llm.verify-model-on-startup` 추가. M8 경계 실험을 Echo 고정·각 5회로 구체화하고 실제 LLM 실험을 별도 표로 분리.
- **Critic MAJOR**: `slack.base-url` 추가(A10 실행 가능), A5 판정을 curl 기준으로 변경, 핸들러 의존을 `AttemptHandle`로 확정하고 Driver 3 재서술, 스파이크 스텁·타임박스·fallback 명시, A15·A16 추가, ngrok 실측을 M0에 추가.
- **Architect MAJOR**: Option A를 A1/A2로 분리(A1 제외), `sendAsync`+`cancel(true)` 및 소켓 종료 판정으로 정정, 예산 소진 경로(A15)와 총 60초(A16) 검증 추가, 3초 뒤 연결 단절 대응(`ack_delivered`), dedup 해제 배치, 스파이크를 M1.5로 이동.
- **MINOR**: FAILED 축출·CAS 전이, A13 grep 범위 확장·경로 고정, 설정 키 확정(`llm.client` 등), 폴백 모델, Slack 구독 자동 비활성화 체크리스트, 정본 `.claude/docs/PLAN.md` 동기화·문서 링크 교정.
- **미반영(사유)**: Architect의 "예산 사실상 무한 배치"는 선택 배치(M8-5)로 격하해 반영. Option 3(콜백)은 기각 근거만 §7에 기록.
