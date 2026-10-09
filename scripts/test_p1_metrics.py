"""scripts/p1-metrics의 구간·6개 지표 검증(합성 로그 픽스처, 외부 호출 없음). 실행: python3 scripts/test_p1_metrics.py (실패하면 종료 코드 1)"""
import os, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "p1-metrics")
NOW = "2026-10-09T10:05:00+09:00"  # = 01:05:00Z. 구간은 5m이면 01:00:00Z ~ 01:05:00Z
P = "  INFO 1 --- [slack-lab] [w-1] c.s.l.c.s.Cls : "

# 옵션 없는 기존 출력의 골든(M38 수정 전 스크립트가 낸 값)
LEGACY_LOG = '2026-10-09T10:00:00.100+09:00  INFO 1 --- [slack-lab] [http-1] c.s.l.a.s.AckLoggingFilter : 수신 응답 ack_delivered=true recv_ms=40\n2026-10-09T10:00:00.200+09:00  INFO 1 --- [slack-lab] [http-1] c.s.l.a.r.Publisher : 큐 저장 확인 enqueue_ms=12\n2026-10-09T10:00:05.000+09:00  INFO 1 --- [slack-lab] [w-1] c.s.l.c.s.EventProcessor : 처리 지표 event_id=EvA attempt_id=at1 gen=0 retries=0 manual_run=false result=Delivered kind=answer finalized=true queue_wait_ms=30 llm_ms=4000 send_ms=120 answer_ms=4300 received_at_missing=false negative_interval=false\n2026-10-09T10:00:06.000+09:00  INFO 1 --- [slack-lab] [w-1] c.s.l.a.s.SlackClient : 반응 성공 reaction_ms=90\n2026-10-09T10:00:20.000+09:00  INFO 1 --- [slack-lab] [w-1] c.s.l.c.s.EventProcessor : 처리 지표 event_id=EvB attempt_id=at2 gen=0 retries=0 manual_run=false result=RetryRequested kind=- finalized=false queue_wait_ms=10 llm_ms=60 send_ms=0 answer_ms=100 received_at_missing=false negative_interval=false\n2026-10-09T10:00:55.000+09:00  INFO 1 --- [slack-lab] [w-1] c.s.l.c.s.EventProcessor : 처리 지표 event_id=EvB attempt_id=at3 gen=0 retries=1 manual_run=false result=Delivered kind=failure_notice finalized=true queue_wait_ms=35000 llm_ms=70 send_ms=100 answer_ms=35200 received_at_missing=false negative_interval=false\n2026-10-09T10:01:00.000+09:00  INFO 1 --- [slack-lab] [w-1] c.s.l.c.s.EventProcessor : 처리 지표 event_id=EvC attempt_id=at4 gen=0 retries=0 manual_run=false result=Delivered kind=answer finalized=true queue_wait_ms=20 llm_ms=6000 send_ms=110 answer_ms=6200 received_at_missing=false negative_interval=false\n2026-10-09T10:01:10.000+09:00  INFO 1 --- [slack-lab] [sched] c.s.l.c.s.BacklogReporter : 적체 스냅샷 queue_ready=2 defer=1 dead=0 retry=3 dlq=0 recovery=0\n2026-10-09T10:01:20.000+09:00  INFO 1 --- [slack-lab] [sched] c.s.l.c.s.BacklogReporter : 적체 스냅샷 queue_ready=5 defer=0 dead=1 retry=4 dlq=1 recovery=2\n2026-10-09T10:01:30.000+09:00  WARN 1 --- [slack-lab] [sched] c.s.l.c.s.BacklogReporter : 적체 스냅샷 실패 reason=IOException\n2026-10-09T10:01:31.000+09:00  WARN 1 --- [slack-lab] [http-1] c.s.l.a.r.Publisher : 발행 실패 event_id=EvD reason=X\n'
LEGACY_GOLDEN = 'recv_ms        n=1    p50=40      p95=40      max=40\nenqueue_ms     n=1    p50=12      p95=12      max=12\nqueue_wait_ms  n=2    p50=20      p95=30      max=30\nllm_ms         n=2    p50=4000    p95=6000    max=6000\nsend_ms        n=2    p50=110     p95=120     max=120\nanswer_ms      n=2    p50=4300    p95=6200    max=6200\nreaction_ms    n=1    p50=90      p95=90      max=90\n\n결과별 건수(시도 단위):\n  Delivered kind=answer: 2\n  Delivered kind=failure_notice: 1\n  RetryRequested kind=-: 1\n  정상 답변 2/4 시도 (50.0%)\n  event_id별 마지막 결과: 정상 답변 2/3 (66.7%), 재시도·재처리 시도로 표본에서 뺀 정상 답변 0건\n발행 실패 1건, 반응 실패 0건, 스냅샷 실패 1건, 음수 구간 0건\n적체(스냅샷 2줄, 워커 수만큼 중복): queue_ready 최대 5, defer 최대 1, dead 최대 1, 마지막 retry=4 dlq=1 recovery=2\n'


def run(log, *args, stdin=False):
    with tempfile.NamedTemporaryFile("w", suffix=".log", delete=False, encoding="utf-8") as f:
        f.write(log)
        path = f.name
    try:
        cmd = [sys.executable, SCRIPT, *args] + ([] if stdin else [path])
        p = subprocess.run(cmd, capture_output=True, text=True, input=log if stdin else None)
        return p.returncode, p.stdout, p.stderr
    finally:
        os.unlink(path)


def line(ts, msg):
    return f"{ts}{P}{msg}\n"


def metric(ts, result, event="EvX", attempt="atX", kind="-", gen=0, retries=0, llm=100, wait=10, ans=200, extra=""):
    return line(ts, f"처리 지표 event_id={event} attempt_id={attempt} gen={gen} retries={retries} manual_run=false result={result} kind={kind} "
                    f"finalized=true queue_wait_ms={wait} llm_ms={llm} send_ms=50 answer_ms={ans} received_at_missing=false negative_interval=false{extra}")


def rag(ts, result, reason=""):
    return line(ts, f"metric=rag_result result={result}" + (f" reason={reason}" if reason else "") + " elapsed_ms=5")


T_IN = "2026-10-09T10:02:00.000+09:00"  # 구간 안
results = []


def check(name, cond, detail=""):
    results.append(bool(cond))
    print(("PASS" if cond else "FAIL"), name, "" if cond else detail)


# 1. 옵션 없는 출력은 수정 전과 같다(회귀 0)
rc, out, _ = run(LEGACY_LOG)
check("옵션 없는 기존 출력이 바뀌지 않음", rc == 0 and out == LEGACY_GOLDEN, out)

# 2. 구간 경계: 직전/직후, Z와 +09:00 혼재
log = (rag("2026-10-09T00:59:59.999Z", "found")            # 직전(01:00:00 미만) → 제외
       + rag("2026-10-09T10:00:00.000+09:00", "found")      # 정확히 시작(01:00:00Z) → 포함
       + rag("2026-10-09T01:04:59.999Z", "none")            # 끝 직전 → 포함
       + rag("2026-10-09T10:05:00.000+09:00", "unavailable", "embedding_timeout")  # 끝(01:05:00Z) → 포함
       + rag("2026-10-09T01:05:00.001Z", "found"))          # 직후 → 제외
rc, out, _ = run(log, "--since", "5m", "--now", NOW)
check("구간 경계·Z/오프셋 혼재", "found=1 none=1 unavailable=1 (호출 3," in out and "구간 밖 제외 2줄" in out, out)

# 3. 시각 없는 줄은 직전 시각 줄에 귀속
cont = "metric=rag_result result=found elapsed_ms=1\n"  # 타임스탬프 없는 줄(스택트레이스 자리에 지표 모양을 넣어 귀속만 본다)
log = line("2026-10-09T00:00:00Z", "구간 밖 줄") + cont + line(T_IN, "구간 안 줄") + cont
rc, out, _ = run(log, "--since", "5m", "--now", NOW)
check("시각 없는 줄은 직전 시각 줄에 귀속", "found=1 none=0 unavailable=0 (호출 1," in out, out)
rc, out, _ = run(cont + line(T_IN, "구간 안 줄"), "--since", "5m", "--now", NOW)
check("파일 첫머리의 시각 없는 줄은 시각 없음으로 센다", "시각 없음 1줄" in out and "RAG 호출 0" in out, out)

# 4. 부분 스냅샷(키 누락) — 죽지 않고 '-'로 표시, 레거시 모드도 죽지 않음
snap_partial = line(T_IN, "적체 스냅샷 queue_ready=3 defer=1")
rc, out, err = run(snap_partial, "--since", "5m", "--now", NOW)
check("부분 스냅샷에서 정상 출력", rc == 0 and "queue_ready=3 defer=1 dead=- retry=- dlq=- recovery=-" in out and "부분 스냅샷" in out, out + err)
rc, out, err = run(snap_partial)
check("레거시 모드도 부분 스냅샷에서 죽지 않음", rc == 0 and "마지막 retry=- dlq=- recovery=-" in out, out + err)

# 5. stale 경고: 31초 → 경고, 29초 → 경고 없음
snap = "적체 스냅샷 queue_ready=0 defer=0 dead=0 retry=0 dlq=0 recovery=0"
rc, out, _ = run(line("2026-10-09T01:04:29Z", snap), "--since", "5m", "--now", NOW)  # 31초 전
check("스냅샷 31초 전은 stale 경고", "(31초 전)" in out and "stale" in out, out)
rc, out, _ = run(line("2026-10-09T01:04:31Z", snap), "--since", "5m", "--now", NOW)  # 29초 전
check("스냅샷 29초 전은 경고 없음", "(29초 전)" in out and "stale" not in out, out)
rc, out, _ = run("", "--since", "5m", "--now", NOW)
check("스냅샷이 없으면 없다고 표시", "스냅샷 없음" in out, out)

# 6. llm_ms=0 포함, -1 제외
log = (metric(T_IN, "Delivered", "E1", "a1", "answer", llm=0) + metric(T_IN, "RetryRequested", "E2", "a2", llm=-1)
       + metric(T_IN, "RetryRequested", "E3", "a3", llm=40))
rc, out, _ = run(log, "--since", "5m", "--now", NOW)
check("llm 시도 시리즈: 0 포함, -1 제외", "llm_attempt_ms n=2 " in out, out)

# 7. S2 형태: 시도 4줄(재시도 3 + 실패 안내 1) → 이벤트 1
log = "".join(metric(T_IN, "RetryRequested", "EvS2", f"at{i}", retries=i, llm=10) for i in range(3)) \
    + metric(T_IN, "Delivered", "EvS2", "at3", "failure_notice", retries=3, llm=10)
rc, out, _ = run(log, "--since", "5m", "--now", NOW)
check("S2 형태: 시도 단위 4줄, 이벤트 단위 실패 안내 1", "재시도 요청: 3" in out and "실패 안내: 1" in out
      and out.split("이벤트 단위")[1].count("실패 안내: 1") == 1 and "llm_attempt_ms n=4" in out, out)

# 8. 기존 'RAG 검색 … result=' 줄이 섞여도 ⑥은 metric=rag_result 줄만 센다, RAG 0줄 표시
old = line(T_IN, "RAG 검색 result=found elapsed_ms=3 hits=2") + line(T_IN, "RAG 검색 result=none elapsed_ms=3")
rc, out, _ = run(old, "--since", "5m", "--now", NOW)
check("기존 RAG 검색 줄은 세지 않고 0줄 표시", "RAG 호출 0 (비활성이거나 검색 생략 — 장애 아님)" in out, out)
rc, out, _ = run(old + rag(T_IN, "found") + rag(T_IN, "unavailable", "vector_store_timeout"), "--since", "5m", "--now", NOW)
check("metric=rag_result만 세고 장애 사유를 보인다", "found=1 none=0 unavailable=1 (호출 2, unavailable 50.0%)" in out
      and "검색 장애 있음: vector_store_timeout=1" in out, out)
rc, out, _ = run(rag(T_IN, "found") + rag(T_IN, "none"), "--since", "5m", "--now", NOW)
check("unavailable 0이면 장애 없음", "검색 장애 없음" in out, out)

# 9. 출력에 event_id·attempt_id 원본이 없다
log = metric(T_IN, "Delivered", "EvSECRET123", "atSECRET456", "answer") + rag(T_IN, "found")
rc, out, _ = run(log, "--since", "5m", "--now", NOW)
check("출력에 event_id·attempt_id 원본 없음", "SECRET123" not in out and "SECRET456" not in out, out)
rc, out, _ = run(log)
check("레거시 출력에도 원본 없음", "SECRET123" not in out and "SECRET456" not in out, out)

# 10. 오류 유도: 깨진 타임스탬프, 빈 입력, negative_interval, 숫자 쓰레기
rc, out, err = run(line("2026-13-45T99:99:99+09:00", "깨진 시각") + line(T_IN, "정상"), "--since", "5m", "--now", NOW)
check("깨진 타임스탬프는 세고 종료하지 않음", rc == 0 and "깨진 타임스탬프 1줄" in out, out + err)
rc, out, err = run("", "--since", "5m", "--now", NOW)
check("빈 입력", rc == 0 and "RAG 호출 0" in out, out + err)
rc, out, err = run("", stdin=True)
check("빈 입력(레거시)", rc == 0 and "recv_ms        n=0" in out, out + err)
rc, out, err = run(metric(T_IN, "Delivered", "E9", "a9", "answer").replace("negative_interval=false", "negative_interval=true"),
                   "--since", "5m", "--now", NOW)
check("negative_interval=true를 표시", rc == 0 and "음수 구간 1건" in out and "측정은 무효" in out, out + err)
rc, out, err = run(line(T_IN, "처리 지표 event_id=E8 result=Delivered kind=answer gen=0 llm_ms=abc queue_wait_ms=1 answer_ms=2")
                   + rag(T_IN, "found"), "--since", "5m", "--now", NOW)
check("숫자 쓰레기 줄은 건너뛰고 나머지는 센다", rc == 0 and "형식 오류로 건너뜀 1줄" in out and "found=1" in out, out + err)

# 11. 잘못된 옵션은 비정상 종료
rc, _, err = run("", "--since", "abc")
check("잘못된 --since는 종료 코드 1", rc != 0, err)
rc, _, err = run("", "--now", NOW)
check("--now만 주면 종료 코드 1", rc != 0, err)

print("전체", "PASS" if all(results) else "FAIL", sum(results), "/", len(results))
sys.exit(0 if all(results) else 1)
