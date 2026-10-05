"""scripts/alert-roundtrip의 판정 로직 검증(가짜 시계·가짜 Slack, 외부 호출 없음). 실행: python3 scripts/test_alert_roundtrip.py (실패하면 종료 코드 1)"""
import os, sys
os.environ.update(SLACK_BOT_TOKEN="x", SLACK_TEST_CHANNEL="C", ALERT_SECRET="s")
HERE = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(HERE, "alert-roundtrip"), encoding="utf-8").read().replace('if __name__ == "__main__":\n    main()\n', "")
ns = {"__file__": os.path.join(HERE, "alert-roundtrip")}
exec(compile(src, "alert-roundtrip", "exec"), ns)
run, SlackError = ns["run"], ns["SlackError"]

class Clock:
    def __init__(self): self.t = 1000.0
    def time(self): return self.t
    def sleep(self, n): self.t += n

def make(report_times, extra=None, fail_auth=False, per_page=100, bot_id_only=False, text="리포트", other_bot=False):
    """report_times: 첫 poll 기준 시각(초)들 — 그 시각 이후 폴링에 해당 메시지가 history에 나타난다."""
    clock = Clock()
    state = {"eid": None, "start": clock.t}
    def send_fn(body):
        import json, hashlib
        msg = json.loads(json.loads(body)["Message"])
        state["eid"] = ns["event_id"](msg["AlarmName"], msg["StateChangeTime"])
        state["start"] = clock.t
        return 200
    def slack_fn(method, q=None):
        if method == "auth.test":
            if fail_auth: raise SlackError("auth.test: invalid_auth")
            return {"user_id": "UBOT", "bot_id": "B1"}
        msgs = []
        for i, t in enumerate(report_times):
            start, end = t if isinstance(t, tuple) else (t, float("inf"))  # (보이기 시작, 사라지는) 초 — 삭제·편집된 메시지 흉내
            if start <= clock.t - state["start"] < end:
                m = {"ts": f"{i+1}.0", "metadata": {"event_payload": {"event_id": state["eid"]}}, "text": text}
                if bot_id_only: m["bot_id"] = "B1"
                else: m["user"] = "UBOT"
                msgs.append(m)
        if other_bot:
            msgs.append({"ts": "99.0", "user": "UOTHER", "bot_id": "B9", "metadata": {"event_payload": {"event_id": state["eid"]}}, "text": "남의 메시지"})
        msgs.append({"ts": "0.5", "user": "UBOT", "text": "이전 답글(메타데이터 없음)"})
        # 페이지네이션: per_page 단위로 자른다
        cur = int((q or {}).get("cursor", 0))
        page = msgs[cur:cur + per_page]
        nxt = cur + per_page
        return {"ok": True, "messages": page, "response_metadata": {"next_cursor": str(nxt) if nxt < len(msgs) else ""}}
    return clock, send_fn, slack_fn

def go(name, expect_code, **kw):
    sendcode = kw.pop("sendcode", None)
    clock, send_fn, slack_fn = make(**kw)
    if sendcode is not None: send_fn = lambda body, c=sendcode: c
    out = []
    code = run("a", "d", "r", 2, clock=clock, send_fn=send_fn, slack_fn=slack_fn, out=out.append)
    ok = code == expect_code
    print(("PASS" if ok else "FAIL"), name, "exit", code, "(expected", expect_code, ") elapsed", round(clock.t - 1000.0, 1), "|", [o for o in out if "봇 메시지" in o or "오류" in o or "정확히" in o or "시간 초과" in o or "실패 안내" in o][:2])
    return ok

results = [
    go("정상: 5초에 리포트 1개, 15초 관찰 후 성공", 0, report_times=[5]),
    go("마감 직전(170초)에 첫 리포트 → 상한과 무관하게 15초 더 관찰", 0, report_times=[170]),
    go("첫 리포트 8초 뒤 늦은 중복 → 실패", 1, report_times=[5, 13]),
    go("관찰 마지막 순간(첫 리포트 +14초)의 중복도 잡는다", 1, report_times=[5, 19]),
    go("마감(180초) 직전 첫 리포트 뒤 182초에 나타난 중복도 잡는다(상한 강제 종료 회귀 방지)", 1, report_times=[170, 182]),
    go("먼저 본 리포트가 이후 응답에서 사라져도 누적해 중복을 센다(폴링마다 초기화 회귀 방지)", 1, report_times=[(5, 9), 16]),
    go("페이지네이션: 중복이 2페이지에 있어도 센다", 1, report_times=[5, 6], per_page=1),
    go("실패 안내 텍스트는 리포트가 아니라 실패", 1, report_times=[5], text="죄송해요, 지금 답변을 만들지 못했어요. 잠시 후"),
    go("수신 거절(401)은 종료 코드 2", 2, report_times=[5], sendcode=401),
    go("연결 실패(-1)도 종료 코드 2", 2, report_times=[5], sendcode=-1),
    go("auth 실패는 종료 코드 3", 3, report_times=[5], fail_auth=True),
    go("리포트가 끝내 없으면 180초 뒤 종료 코드 1", 1, report_times=[]),
    go("user 없는 봇 메시지도 bot_id로 인식", 0, report_times=[5], bot_id_only=True),
    go("다른 봇이 같은 event_id 메타데이터를 달아도 세지 않는다", 0, report_times=[5], other_bot=True),
]
print("전체", "PASS" if all(results) else "FAIL", sum(results), "/", len(results))
sys.exit(0 if all(results) else 1)
