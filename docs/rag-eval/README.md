# RAG 평가 데이터 (3단계)

공개 저장소에 커밋하는 **가상** 데이터다. 실제 장애 문서·내부 정보를 넣지 않는다(실제 문서는 저장소 밖 경로 `RAG_DOCS_DIR`로 색인한다).

- `documents/` — 가상 장애 문서 20개(한국어·영어·혼합). `scripts/rag-eval`이 이 문서로 색인을 바꾼 뒤 평가한다.
- `questions.json` — 질문과 정답 문서 ID. `final`(합격 판정용 30개)과 `tuning`(조정용 10개)은 **겹치지 않는다**.

## 규격 (PRD 3단계 범위)

| 세트 | 질문 | 알람형 | 멘션형 | 정답 있음 | 정답 없음 | 쓰임 |
|---|---|---|---|---|---|---|
| final | 30 | 15 | 15 | 24 | 6 | 합격 판정 |
| tuning | 10 | 5 | 5 | 8 | 2 | K·청크 크기·검색 임계값 조정 |

- **hit@3**: 정답 있는 질문에서 정답 문서가 임계값 적용 전 검색 순위의 문서 3개 안에 있는가. 합격선은 24개 중 **20개 이상**.
- **근거 미주입**: 정답 없는 질문에서 임계값·문맥 상한을 거친 뒤 주입된 조각이 0건인가. 합격선은 6개 중 **5개 이상**.
- 검색 불가(임베딩·저장소 오류)가 1건이라도 있으면 판정하지 않고 불합격으로 본다.
- **합격선은 미리 고정한다.** 실측으로 바꿀 수 있는 것은 K·청크 크기·검색 임계값뿐이고, 그 조정은 `tuning` 세트로만 한다. `final` 결과를 보고 기준이나 정답 라벨을 바꾸지 않는다.
- 이 수치는 기능 검증·회귀 감지용이다. 질문이 적어 일반적인 검색 품질을 보장하지 않는다.

알람형 질문은 운영과 같은 모양의 알람 메시지(고정 안내문 + 알람 블록)로 만들어져 검색 질의 추출까지 운영 경로를 그대로 탄다.

## 실행

```bash
# 평가용 DB(별도 compose 프로젝트 등)에서만. 현재 색인을 가상 문서로 바꾼다. POSTGRES_URL을 명시해야 실행한다(기본 DB 보호).
export POSTGRES_URL=jdbc:postgresql://localhost:5432/slacklab RAG_ENABLED=true RAG_EMBEDDING_MODEL=bge-m3 RAG_EMBEDDING_DIMENSION=1024
scripts/rag-eval --confirm-eval-db                       # 기본: tuning 세트만 (임계값 등을 조정하는 동안 final을 보지 않는다)
scripts/rag-eval --confirm-eval-db --eval.set=final      # 합격 판정: final만
scripts/rag-eval --confirm-eval-db --eval.set=all        # 둘 다
scripts/rag-eval --confirm-eval-db --eval.pairs          # 답변 쌍(RAG 끔/켬) 사람 비교용 빈 표
```

종료 코드: 0 합격(또는 final 미실행), 1 불합격, 2 검색 불가·입력 오류·**final이 규격(정답 24 + 정답 없음 6)이 아님**. 색인에 변화가 없으면 임베딩 호출이 없어 모델이 데워지지 않으므로 평가기가 시작할 때 몇 번 데운다.

## 답변 쌍 기록 (P2-4)과 사람 점검

자동 판정은 검색 단계만 한다. 답변 단계는 로컬 모델의 표현 변동이 커서 문자열 채점 대신 사람이 같은 질문의 RAG 끔/켬 답변을 나란히 보고 `--eval.pairs`가 출력한 표에 적는다.

- **근거 활용**: RAG 켬 답변이 참고 문서의 구체 내용(수치·절차)을 담았는가 (O/X, 정답 없는 질문은 해당없음)
- **근거 없음 안내**: 정답 없는 질문에서 억지로 문서를 끼우지 않고 "관련 문서 근거를 찾지 못해" 안내가 붙었는가 (O/X)
- **참고 문서 형식**: 답글의 "참고 문서" 줄(주입한 문서만, 문서 ID 기준 중복 없음, 경로·원문 미노출)은 서버가 붙이고 형식은 `SlackFooterRendererTest`가 고정한다. 사람이 기록할 때는 목록이 주입된 문서와 맞는지만 눈으로 확인한다. 내용 채점은 하지 않는다.

## 알려진 한계

- tuning 세트의 정답 없는 질문은 2개뿐이라 임계값 조정의 근거가 약하다. 조정은 "상한선을 넘기는 값을 찾는" 거친 용도로만 쓴다.
- tuning과 final은 같은 문서(지식 베이스)를 공유하므로 정답 문서가 겹친다(질문은 겹치지 않는다). 의역 수준의 유사 질문은 사람이 확인해야 한다.

## 분류 평가 세트 (4단계 M32)

`classification.json`(tuning 30 + final 60)과 `classification-labels.md`(라벨 정의·경계·합격선)가 4단계 요청 분류를 위한 세트다. 3단계 `questions.json`과 질문이 겹치지 않는다(`ClassificationSetTest`가 고정). **final은 blob 해시 `e8746b557d667e9fdbc6ded658e6cc769a0f30f5`(`git hash-object docs/rag-eval/classification.json`)로 고정**하며 프롬프트 튜닝에 쓰지 않는다. 라벨 정의 문서의 해시는 `b974f9898bd5c612aeb048a5d4d596c3896f4fac`.

```bash
# 분류 off 주입 기준선(LLM 불필요, bge-m3 임베딩만). 평가용 DB에서만 — 색인을 가상 문서로 교체한다.
POSTGRES_URL=jdbc:postgresql://localhost:5432/slacklab RAG_ENABLED=true RAG_EMBEDDING_MODEL=bge-m3 RAG_EMBEDDING_DIMENSION=1024 \
  scripts/classify-baseline --confirm-eval-db [--eval.set=tuning|final|all]
```

정확도 하니스(`ClassificationEvaluator`)는 코어에 있고 분류기 어댑터는 M33에서 붙는다(`scripts/classify-eval`은 그때 추가).

