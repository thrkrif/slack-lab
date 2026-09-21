# AGENTS.md

모든 에이전트(Claude Code, Codex 등)가 공유하는 원본 규칙이다. `CLAUDE.md`는 이 파일을 참조만 한다.

Slack 이벤트를 트리거로 AI가 답하는 구조를 단계적으로 만드는 학습용 프로젝트.

- 요구사항 → [`PRD.md`](.claude/docs/PRD.md)
- 구조·결정 기록 → [`ARCHITECTURE.md`](.claude/docs/ARCHITECTURE.md)
- 작업 계획 → [`PLAN.md`](.claude/docs/PLAN.md)
- 실험 기록 → [`docs/EXPERIMENT-LOG.md`](docs/EXPERIMENT-LOG.md)

**현재 단계: 1단계 (동기) · 구현 전.** 큐·n8n·RAG 없음. 단계가 바뀌면 이 줄을 갱신한다.

## 규칙

1. **단계를 앞지르지 않는다.** 1단계 코드에 큐·RAG·LangGraph를 미리 넣지 않는다.
   필요하면 코드 대신 주석으로 `// N단계에서 여기가 바뀐다`만 남긴다.
2. **`SlackEventHandler`는 HTTP를 모른다.** `HttpServletRequest`·응답 코드가 들어오면 안 된다.
   2단계에서도 HTTP·큐 ACK와 분리한다. 워커 연동을 위한 결과 타입·호출부 변경은 허용한다.
   `SENDING` 기록을 위해 상태 저장소 **인터페이스**에 의존하는 것은 허용하며, `jakarta.servlet`·`HttpStatus` import 여부로 검사한다.
3. **LLM 벤더를 코드에 박지 않는다.** OpenAI 호환 스키마로만 호출하고, 교체는 `llm.base-url`로 한다.
4. **`ARCHITECTURE.md` §8의 함정 10가지를 처음부터 피한다.** 특히 1·2·3번(dedup 확정 시점,
   발신 실패 누출, 조용한 실패)은 1단계에서 바로 지킨다. 구체적인 단계별 보장 범위는 PRD §5를 따른다.
   P0는 인메모리 중복 억제이며, 재시작·큐 재전달 복구는 P1이다. 전송 결과 불명은 성공이나 명확한 실패로 간주하지 않는다.
5. **정상 흐름은 외부 왕복 성공, 실패 흐름은 오류 유도 후 기대 동작 확인으로 완료를 판정한다.**
   빌드 통과만으로 완료 처리하지 않는다. 성능·중복·복구 검증 조건은 PRD §5를 따른다.
6. **비밀값은 환경변수.** `.env`는 커밋하지 않는다.
7. **측정값은 로그로 남긴다.** 재전송 헤더, LLM 소요 시간 — 실험 기록에 쓸 값들.
8. **주석은 "왜"를 쓴다.** 무엇을 하는지는 코드가 말한다.
9. **구조를 바꾸면 `ARCHITECTURE.md`를, 실험하면 `EXPERIMENT-LOG.md`를 같은 작업에서 갱신한다.**
10. **P0 처리 상한은 60초다.** 인위적 지연·LLM 합계 최대 50초, Slack 전송 예산 10초.
    실패 안내도 같은 예산 안에서 1회 시도하며, 안내 전송 실패·결과 불명은 event_id와 실패 단계를 로그에 남긴다.
11. **P1은 큐 저장 확인 후 수신 ACK, 처리 상태 저장 후 큐 ACK한다.** 전송 결과 불명은 자동 재발신하지 않고 복구 대상으로 남긴다. (`ARCHITECTURE.md` §3·§5)
12. **성능 수치는 검증 환경과 함께 기록한다.** PRD §5의 장비·모델·부하 조건을 고정하고 p95로 판정한다.

## Git 규칙

이 저장소는 **public**이다. 한 번 push한 비밀값·실명·로컬 경로는 지워도 이력에 남는다.

- **작업 단위마다 브랜치 → 커밋 → push → PR까지 한다.** 사용자의 별도 요청은 필요 없다.
  작업 단위는 검증 가능한 한 덩어리(마일스톤 또는 그 일부)이며, 한 커밋·PR에 서로 다른 작업을 섞지 않는다.
  **`main` 병합과 태그는 사용자가 요청했을 때만 한다.** `develop` 병합은 PR 승인 후 사용자가 요청할 때 한다.
- **브랜치**: `main`(단계 완료 시점만, 직접 push 금지) ← `develop`(통합) ← `feature/<마일스톤>-<주제>`(예: `feature/m2-signature`).
  `release/*`·`hotfix/*`는 두지 않는다. 기능 브랜치는 `develop`에서 분기해 PR로 `develop`에 squash merge하고,
  `develop` → `main`은 단계 완료 때 merge commit + 태그(예: 1단계 완료 `v0.1.0`)로 올린다.
- **커밋 메시지**: Conventional Commits. 타입은 영어, 제목은 한글 (`feat: 서명 검증기 추가`).
  타입은 `feat` `fix` `docs` `test` `refactor` `chore` 중 하나. 제목에 마침표를 붙이지 않는다.
- **커밋 전 `git diff --cached`로 확인한다**: 토큰·시크릿, 실명, 로컬 절대경로(`/Users/...`), Slack 채널 ID·event_id 원본.
- 린트·시크릿 검사(Spotless, gitleaks)와 CI는 M1에서 Gradle 골격을 만들 때 함께 정하고, 정해지면 이 절과 명령어 절을 갱신한다.
  커밋 훅(pre-commit)으로 걸지도 이때 함께 정한다. CI 통과는 완료 기준이 아니다(규칙 5).
  그 전까지는 위 diff 점검과 `.gitignore`가 유일한 방어선이다. 비밀값은 `.env`에만 두고 `.env.example`에는 키 이름만 적는다.
- **PR**: 제목은 커밋 규칙과 같다. 본문에 변경 요약과 검증 결과(규칙 5)를 적는다. 대상은 `develop`이다.
- **세션**: 작업 단위마다 새 세션에서 시작한다. 단위를 끝낼 때 `.claude/docs/PLAN.md`의 진행 상태와 `docs/EXPERIMENT-LOG.md`를 갱신한다.
  다음 세션은 이 두 파일을 읽고 이어간다. 대화에만 남은 상태는 이어지지 않는다.
  PLAN.md 진행 상태의 `다음 세션 핸드오프` 소절도 함께 **덮어쓴다**(누적 금지, 10줄 안팎). 별도 handoff 파일은 두지 않는다.
  사람 확인이 필요한 지점(Slack 화면 조작, ngrok URL 재등록, PR 병합)에서는 자동으로 넘어가지 않고 멈춘다.

## 명령어

```bash
./gradlew build                                   # 컴파일 + 테스트
set -a && source .env && set +a && ./gradlew bootRun
ngrok http 8080                                   # 별도 터미널
ollama serve                                      # 별도 터미널
ollama list                                       # 설정에 박을 모델 ID는 여기서 확인
```

## 스택 (검증된 조합 — `ARCHITECTURE.md` §7)

Java 21 · Spring Boot 3.4.1 · Gradle Wrapper 8.14.3 · Spring Web (MVC, **동기 유지**) · Lombok
LLM: Ollama 로컬 추론 (`http://localhost:11434/v1`, OpenAI 호환) — **API 비용 0원이 요구사항**

Lombok은 `compileOnly` + `annotationProcessor` 양쪽 선언 필요.
설정은 `record` + `@ConfigurationProperties` + `@ConfigurationPropertiesScan`.

## 로컬 도구

`.codex/`와 `.omx/`는 선택적인 로컬 개발 도구 설정·상태이며 앱 실행에 필요하지 않고 Git에 포함하지 않는다.

## 함정

- 서명 검증은 **raw body**로. 파싱 후 재직렬화하면 서명이 깨진다
- `bot_id`·`subtype` 있는 이벤트를 거르지 않으면 무한 루프
- Slack API는 실패해도 HTTP 200. 본문 `ok` 필드를 봐야 한다
- 스코프를 추가하면 앱 **재설치**해야 토큰에 반영된다
- ngrok URL은 재시작마다 바뀐다 → Slack Request URL 재등록
- Ollama 첫 호출은 모델 적재로 느리다. `keep_alive` + 기동 시 워밍업
- **`RestClient` 타임아웃과 전체 처리 제한을 함께 적용한다.** 연결·읽기·인위적 지연의 합이 PRD §5의 예산을 넘지 않게 하고, 제한 이후의 LLM 결과는 전송하지 않는다
- 모델 ID는 추측하지 말고 `ollama list` 결과를 쓴다
