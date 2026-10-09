"""n8n 예제(docs/n8n/cloudwatch-alert.json)와 scripts/n8n-watch의 오프라인 검증. 외부 호출 0(실제 Slack·n8n·네트워크를 쓰지 않는다).
Code 노드 JS는 호스트 node로 실행한다 — n8n 안에서의 실행 본문은 M42b에서 다시 대조한다. 실행: python3 scripts/test_n8n_example.py (실패하면 종료 코드 1)"""
import datetime, json, os, re, shutil, subprocess, sys, tempfile, types

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
WORKFLOW = os.path.join(ROOT, "docs", "n8n", "cloudwatch-alert.json")
results = []


def check(name, cond, detail=""):
    results.append(bool(cond))
    print(("PASS" if cond else "FAIL"), name, "" if cond else detail)


def load(name, extra_env=None):
    """scripts/<name>을 수정 없이 exec로 불러온다(test_alert_roundtrip.py와 같은 방식)."""
    os.environ.update(SLACK_BOT_TOKEN="dummy-token", SLACK_TEST_CHANNEL="CDUMMY", ALERT_SECRET="dummy-secret", ALERT_CHANNEL="CDUMMY")
    os.environ.update(extra_env or {})
    path = os.path.join(HERE, name)
    src = open(path, encoding="utf-8").read()
    src = re.sub(r'if __name__ == "__main__":\n.*\n?', "", src)
    ns = {"__file__": path}
    exec(compile(src, name, "exec"), ns)
    return ns


# --- (a) 워크플로 구조 ---------------------------------------------------------------------------
wf_text = open(WORKFLOW, encoding="utf-8").read()
wf = json.loads(wf_text)
nodes = {n["name"]: n for n in wf["nodes"]}
types_ = sorted(n["type"] for n in wf["nodes"])
check("노드 3종(Manual Trigger·Code·HTTP Request)", types_ == ["n8n-nodes-base.code", "n8n-nodes-base.httpRequest", "n8n-nodes-base.manualTrigger"], str(types_))
order = []
cur = "Manual Trigger"
while cur:
    order.append(cur)
    nxt = wf["connections"].get(cur, {}).get("main", [[]])[0]
    cur = nxt[0]["node"] if nxt else None
check("연결 순서: Manual Trigger → 합성 알람 → 알람 API 호출", [nodes[n]["type"].split(".")[-1] for n in order] == ["manualTrigger", "code", "httpRequest"], str(order))
http = next(n for n in wf["nodes"] if n["type"].endswith("httpRequest"))["parameters"]
check("HTTP: POST /alerts/cloudwatch, JSON, Header Auth", http["method"] == "POST" and http["url"].endswith("/alerts/cloudwatch")
      and http["contentType"] == "json" and http["authentication"] == "genericCredentialType" and http["genericAuthType"] == "httpHeaderAuth", str(http))
check("주소는 host.docker.internal(컨테이너 → 호스트)", "host.docker.internal" in http["url"], http["url"])

# --- (b) 시크릿 0건 ------------------------------------------------------------------------------
check("Credential 데이터·ID 참조가 없다", "credentials" not in wf_text and not any("credentials" in n for n in wf["nodes"]))
check("Slack 토큰 패턴 0건", not re.search(r"xox[a-z]-", wf_text))
real = {}
envfile = os.path.join(ROOT, ".env")
if os.path.exists(envfile):
    for line in open(envfile, encoding="utf-8"):
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.split("=", 1)
            real[k.strip()] = v.strip().strip('"').strip("'")
leaked = [k for k in ("ALERT_SECRET", "SLACK_BOT_TOKEN", "SLACK_SIGNING_SECRET", "POSTGRES_PASSWORD") if len(real.get(k, "")) >= 6 and real[k] in wf_text]
check("실제 .env의 비밀값이 JSON에 없다(값은 출력하지 않는다)", not leaked, f"키: {leaked}")

# --- (c) 고정 입력에서 alert-roundtrip과 본문 동일성 ------------------------------------------------
rt = load("alert-roundtrip")
FIXED = datetime.datetime(2026, 10, 9, 1, 2, 3, 456000, tzinfo=datetime.timezone.utc)


class FakeDatetime(datetime.datetime):
    @classmethod
    def now(cls, tz=None):
        return FIXED


class FixedClock:
    def time(self): return 1760000000.123
    def sleep(self, n): pass


captured = []
rt["datetime"] = types.SimpleNamespace(datetime=FakeDatetime, timezone=datetime.timezone)


def send_fn(body):
    captured.append(body)
    return 500  # 200이 아니므로 run()이 관찰 루프에 들어가지 않고 곧바로 종료 코드 2로 끝난다


def slack_fn(method, q=None):
    assert method == "auth.test", f"외부 호출 시도: {method}"
    return {"user_id": "UBOT"}


NAME, DESC, REASON = "fixture-alarm", "고정 입력 설명", "고정 입력 사유"
rc = rt["run"](NAME, DESC, REASON, 3, clock=FixedClock(), send_fn=send_fn, slack_fn=slack_fn, out=lambda *a: None)
check("alert-roundtrip에서 고정 본문을 얻는다(외부 호출 0, 4번 전송 시도)", rc == 2 and len(captured) == 4 and len(set(captured)) == 1, f"rc={rc} n={len(captured)}")

node = shutil.which("node")
js_runner = r"""
const fs = require('fs');
const wf = JSON.parse(fs.readFileSync(process.argv[1], 'utf8'));
const code = wf.nodes.find(n => n.type === 'n8n-nodes-base.code').parameters.jsCode;
const input = JSON.parse(process.argv[2]);
const fn = new Function('$input', code);
console.log(JSON.stringify(fn({ first: () => ({ json: input }) })));
"""


def run_node(fixture):
    p = subprocess.run([node, "-e", js_runner, WORKFLOW, json.dumps(fixture)], capture_output=True, text=True)
    return p.returncode, p.stdout, p.stderr


def normalize(body):
    outer = json.loads(body)
    outer["Message"] = json.loads(outer["Message"])  # Message는 한 번 더 파싱해 비교한다(Python·JS의 직렬화 차이를 배제)
    return outer


if not node:
    check("host node가 있어야 Code 노드를 실행해 본다", False, "node를 찾을 수 없다")
else:
    fixture = {"AlarmName": NAME, "AlarmDescription": DESC, "NewStateReason": REASON,
               "StateChangeTime": "2026-10-09T01:02:03.456+0000", "MessageId": "m-1760000000123"}
    code, out, err = run_node(fixture)
    items = json.loads(out) if code == 0 else []
    check("Code 노드가 아이템 4개(최초 1 + 재전송 3)를 낸다", len(items) == 4, err or out)
    if items:
        py = normalize(captured[0])
        js = [normalize(json.dumps(i["json"], ensure_ascii=False)) for i in items]
        check("n8n 본문과 alert-roundtrip 본문의 필드·값 불일치 0", all(j == py for j in js), f"py={py}\njs={js[0]}")
        check("재전송 간 본문 차이 0", all(j == js[0] for j in js))
        check("Message는 JSON '문자열'이다(객체가 아니다)", all(isinstance(i["json"]["Message"], str) for i in items))
        check("본문 고정 사항(계정·리전·상태·Trigger)", js[0]["Message"]["AWSAccountId"] == "000000000000" and js[0]["Message"]["Region"] == "ap-northeast-2"
              and js[0]["Message"]["NewStateValue"] == "ALARM" and js[0]["Message"]["Trigger"] == {"MetricName": "PendingThreads", "Namespace": "Custom/Hikari"})
    # 입력이 없으면 현재 시각으로 만든다
    code, out, err = run_node({})
    items = json.loads(out) if code == 0 else []
    msg = json.loads(items[0]["json"]["Message"]) if items else {}
    check("입력이 없으면 현재 시각 형식(…SSS+0000)과 m-<ms> MessageId", bool(items) and re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}\+0000", msg.get("StateChangeTime", ""))
          and re.fullmatch(r"m-\d+", items[0]["json"]["MessageId"]) and len({json.dumps(i) for i in items}) == 1, out)
    # 서버의 중복 키 규칙과 일치하는 event_id가 계산된다(알람 이름·시각이 같으면 같은 이벤트)
    check("같은 입력의 4개는 같은 event_id", len({rt["event_id"](json.loads(i["json"]["Message"])["AlarmName"], json.loads(i["json"]["Message"])["StateChangeTime"]) for i in items}) == 1)

# --- (d) n8n-watch 판정 로직(가짜 Slack·가짜 시계·가짜 로그) ---------------------------------------
w = load("n8n-watch")
state_dir = tempfile.mkdtemp()
STATE = os.path.join(state_dir, "state.json")
EID = rt["event_id"]("watch-alarm", "2026-10-09T01:02:03.456+0000")
PUB = "2026-10-09T01:02:03.500+00:00"
PUB_EPOCH = w["log_epoch"](f"{PUB}  INFO x")
json.dump({"oldest": PUB_EPOCH - 1}, open(STATE, "w"))  # start가 Execute 직전에 적는 값
LOG_OK = [f"{PUB}  INFO 1 --- [x] c.s.l.a.AlertController : 알람 발행 완료 event_id={EID} source=cloudwatch"]
LOG_401 = [f"2026-10-09T01:02:04.000+00:00  WARN 1 --- [x] c.s.l.a.AlertController : 알람 인증 실패 → 401 source=cloudwatch"] * 4
ENV = {"SLACK_BOT_TOKEN": "dummy-token", "ALERT_CHANNEL": "CDUMMY"}


class Clock:
    def __init__(self): self.t = PUB_EPOCH - 1
    def time(self): return self.t
    def sleep(self, n): self.t += n


def report(offset, text="알람 분석 리포트", eid=EID):
    return {"ts": f"{PUB_EPOCH + offset:.6f}", "user": "UBOT", "text": text, "metadata": {"event_payload": {"event_id": eid}}}


def judge(expect, msgs, log, fail=False):
    def sl(method, q=None):
        if fail:
            raise rt["SlackError"]("conversations.history: ratelimited")
        if method == "auth.test":
            return {"user_id": "UBOT", "bot_id": "B1"}
        return {"messages": msgs}
    import io, contextlib
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        code = w["cmd_judge"](["watch-alarm", "2026-10-09T01:02:03.456+0000", "--expect", expect, "--log", "x", "--state", STATE], ENV,
                              clock=Clock(), slack_fn=sl, log_lines=log, rt=rt)
    return code, buf.getvalue()


code, out = judge("1", [report(10)], LOG_OK)
check("expect 1: 10초 만에 리포트 1개 → 합격", code == 0, out)
code, out = judge("1", [report(179)], LOG_OK)
check("expect 1: 179초(경계 안) → 합격", code == 0, out)
code, out = judge("1", [report(181)], LOG_OK)
check("expect 1: 181초 → 불합격", code == 1 and "180초" in out, out)
code, out = judge("1", [report(10), report(12)], LOG_OK)
check("expect 1: 리포트 2개 → 불합격", code == 1 and "2개" in out, out)
code, out = judge("1", [report(10, "죄송해요, 지금 답변을 만들지 못했어요")], LOG_OK)
check("expect 1: 실패 안내 → 불합격", code == 1 and "실패 안내" in out, out)
code, out = judge("1", [], LOG_OK)
check("expect 1: 리포트 없음 → 불합격(시간 초과)", code == 1 and "시간 초과" in out, out)
code, out = judge("1", [report(10)], [])
check("expect 1: 수신 로그에 발행 완료 줄이 없으면 종료 코드 2", code == 2, out)
code, out = judge("1", [report(10, eid="alert-0000000000000000000000000000dead")], LOG_OK)
check("expect 1: 다른 알람의 리포트는 세지 않는다", code == 1, out)
code, out = judge("1", [report(10)], LOG_OK, fail=True)
check("Slack 오류 → 종료 코드 3", code == 3, out)
code, out = judge("0", [], LOG_401)
check("expect 0: 401 1줄 이상 + 발행 0 + 리포트 0 → 합격", code == 0, out)
code, out = judge("0", [report(5)], LOG_401)
check("expect 0: 리포트가 있으면 불합격", code == 1, out)
code, out = judge("0", [], LOG_401 + LOG_OK)
check("expect 0: 발행 완료 줄이 있으면 불합격", code == 1, out)
code, out = judge("0", [], [])
check("expect 0: 401 줄이 없으면 불합격", code == 1, out)
old401 = ["2026-10-08T00:00:00.000+00:00  WARN 1 --- [x] c.s.l.a.AlertController : 알람 인증 실패 → 401 source=cloudwatch"]
code, out = judge("0", [], old401)
check("expect 0: 관찰 시작 이전의 401 줄은 세지 않는다", code == 1, out)
check("출력에 event_id·채널 ID·토큰이 없다", all(s not in out for s in (EID, "CDUMMY", "dummy-token")))

# 사전 조건과 상태 파일
check("ALERT_CHANNEL이 비어 있으면 거부", w["watch_env"]({}, {"SLACK_BOT_TOKEN": "t", "ALERT_CHANNEL": ""}) is None)
check("ALERT_CHANNEL이 없으면 거부", w["watch_env"]({}, {"SLACK_BOT_TOKEN": "t"}) is None)
check("토큰이 없으면 거부", w["watch_env"]({}, {"ALERT_CHANNEL": "C"}) is None)
check("프로세스 환경이 .env보다 우선", w["watch_env"]({"ALERT_CHANNEL": "CENV"}, {"SLACK_BOT_TOKEN": "t", "ALERT_CHANNEL": "CFILE"})["ALERT_CHANNEL"] == "CENV")
check("start가 관찰 시작 시각(−1초)을 상태 파일에 쓴다", w["cmd_start"](["--state", os.path.join(state_dir, "s2.json")], ENV, clock=Clock()) == 0
      and abs(json.load(open(os.path.join(state_dir, "s2.json")))["oldest"] - (PUB_EPOCH - 2)) < 1e-6)
check("상태 파일이 저장소 안이면 거부", w["cmd_start"](["--state", os.path.join(ROOT, "x-state.json")], ENV, clock=Clock()) == 2 and not os.path.exists(os.path.join(ROOT, "x-state.json")))
code = w["cmd_judge"](["a", "b", "--expect", "1", "--log", "x", "--state", os.path.join(state_dir, "none.json")], ENV, clock=Clock(), log_lines=[], rt=rt)
check("start 없이 judge하면 종료 코드 2", code == 2)
shutil.rmtree(state_dir, ignore_errors=True)

print("전체", "PASS" if all(results) else "FAIL", sum(results), "/", len(results))
sys.exit(0 if all(results) else 1)
