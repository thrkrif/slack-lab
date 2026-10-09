# n8n 예제: 기존 알람 API를 워크플로에서 호출하기

알람 직접 수신(`POST /alerts/{source}`)은 이미 구현되어 있어서 n8n이 **필수가 아니다**(ADR-9, n8n은 선택 어댑터). 이 예제는 기능을 더하는 것이 아니라 **시각적 워크플로에서 같은 API를 부르는 연결 방법**을 배우기 위한 것이다. Java 코드와 `compose.yaml`은 바뀌지 않았고, n8n은 검증할 때만 띄웠다 내린다(기본 스택에 상시 추가하지 않는다).

## 파일

| 파일 | 역할 |
|---|---|
| `cloudwatch-alert.json` | 워크플로: Manual Trigger → Code(합성 CloudWatch SNS 알람) → HTTP Request |
| `../../scripts/test_n8n_example.py` | 오프라인 검사(구조·시크릿 0건·`alert-roundtrip`과 본문 동일성·판정 로직). 외부 호출 없음 |
| `../../scripts/n8n-watch` | 실제 Slack 알람 채널을 읽어 리포트 수·시간을 판정(사람 단계용) |

JSON은 아래 고정 이미지에서 import한 뒤 export한 결과와 노드·연결·설정이 같다. 비밀값과 Credential 연결은 들어 있지 않다.

## 호출 계약

- `POST http://host.docker.internal:8080/alerts/cloudwatch` (compose로 수신 서버를 띄웠다면 8080 대신 실제 `RECEIVER_PORT`; Docker Desktop이 아니면 `host.docker.internal`이 없을 수 있다)
- `Content-Type: application/json`, 헤더 `X-Alert-Secret`(서버의 `ALERT_SECRET`과 같은 값)
- 본문: `{"Type":"Notification","MessageId":"m-…","Message":"<알람 객체를 JSON 문자열로 직렬화한 값>"}`. `Message` 안에는 `AlarmName`·`AlarmDescription`·`NewStateValue:"ALARM"`·`NewStateReason`·`StateChangeTime`·`Region:"ap-northeast-2"`·`AWSAccountId:"000000000000"`·`Trigger`가 있다.
- 서버는 `계정|리전|AlarmName|StateChangeTime`으로 중복을 억제한다. n8n은 키를 보내지도 중복 처리를 구현하지도 않는다. Code 노드는 실행당 본문을 **한 번만** 만들어 같은 본문의 아이템 4개(최초 1 + 재전송 3)를 내고, 4번 보내도 리포트는 1개여야 한다.
- **HTTP 200은 수신 확인일 뿐 리포트 완성이 아니다.** 리포트는 LLM이 만든 뒤 알람 채널에 올라온다.

## 실행

서버 쪽 준비: `.env`에 `ALERT_SECRET`과 `ALERT_CHANNEL`을 둘 다 채운다(`scripts/env-check`로 점검). 알람 리포트도 LLM을 부르므로 수신 서버(`bootRun`)와 Ollama가 떠 있어야 한다.

```bash
# 이미지는 버전과 digest로 고정한다(latest·stable·무태그 금지). 이 예제는 n8n 2.42.6에서 확인했다.
docker run -d --rm --name slack-lab-n8n -p 127.0.0.1:5678:5678 --memory 1g --cpus 1 \
  -e N8N_DIAGNOSTICS_ENABLED=false -e N8N_VERSION_NOTIFICATIONS_ENABLED=false \
  -v slack-lab-n8n-check:/home/node/.n8n \
  n8nio/n8n:2.42.6@sha256:526daa38b68e923cc00c5280d18b4da5d489f115a73bdbf3b8e452b184197a9a
```

1. 브라우저에서 `http://127.0.0.1:5678`을 열고 owner 계정을 만든다(로컬 검증용).
2. 워크플로 → **Import from file** → `cloudwatch-alert.json`.
3. **Credential을 사람이 만든다**(시크릿을 파일·대화·커밋에 두지 않는다): Credentials → Create → **Header Auth** → Name `X-Alert-Secret`, Value는 `.env`의 `ALERT_SECRET`과 같은 값. 그다음 "알람 API 호출" 노드의 Credential로 선택한다.
4. (관찰 준비) 수신 서버 로그를 파일로 남겨 둔다: `set -a && source .env && set +a && ./gradlew bootRun 2>&1 | tee -a <저장소 밖 경로>/receiver.log`. 그리고 Execute **전에** `scripts/n8n-watch start`.
5. **Execute workflow**. HTTP Request가 4번 호출되고 모두 200이어야 한다.
6. "합성 알람 만들기" 노드 출력의 `Message`에서 `AlarmName`·`StateChangeTime`을 복사해(시크릿이 아니다) 판정한다:
   ```bash
   scripts/n8n-watch judge <AlarmName> <StateChangeTime> --expect 1 --log <저장소 밖 경로>/receiver.log
   ```
   합격: 수신 로그의 첫 `알람 발행 완료`부터 첫 리포트까지 180초 이내, 그 뒤 15초 관찰해 이 알람의 봇 메시지가 정확히 1개, 실패 안내 없음.
7. **401 관찰**: Credential의 값을 일부러 틀리게 바꿔 다시 `n8n-watch start` → Execute → `scripts/n8n-watch judge … --expect 0 --log …`. 합격: 수신 로그에 `알람 인증 실패 → 401`이 1줄 이상, `알람 발행 완료` 0줄, 60초 동안 리포트 0개. (n8n 2.42.6의 실행에서는 4건 모두 401을 받은 뒤 실행이 실패로 끝났다.)

## 끝내기

```bash
docker stop slack-lab-n8n
docker volume rm slack-lab-n8n-check
```

컨테이너는 `--rm`이라 멈추면 사라지고, 검증 전용 볼륨까지 지워야 Credential이 남지 않는다. 이 예제를 위해 메모리를 늘 쓰지 않는다(상한 1GiB).

## 한계

- 합성 알람이다. 실제 AWS 계정·CloudWatch 서명은 쓰지 않는다.
- Code 노드의 본문 동일성 검사(`test_n8n_example.py`)는 호스트 `node`로 같은 JS를 실행한다. n8n 안에서 실행된 본문과의 대조는 사람 단계에서 `n8n-watch`의 리포트 1개 판정으로 한 번 더 확인한다.
- n8n 버전이 바뀌면 노드 `typeVersion`이 달라질 수 있다. 새 버전에서는 이미지를 다시 고정하고 import·export로 확인한다.
