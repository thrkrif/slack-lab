"""docs/slack-app-manifest.json 정적 검증(외부 호출 없음). 코드가 실제로 부르는 Slack 메서드를 뽑아 '메서드 → 필요 스코프' 표와 대조한다.
실행: python3 scripts/test_manifest.py (실패하면 종료 코드 1)"""
import copy, json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
MANIFEST = os.path.join(ROOT, "docs", "slack-app-manifest.json")

# 메서드 → 필요 봇 스코프(공개 채널 기준). None이면 스코프가 필요 없다. 표에 없는 메서드가 코드에 나오면 실패한다 —
# 새 Slack 호출을 추가한 사람이 스코프와 Manifest를 함께 고치게 하려는 장치다.
METHOD_SCOPE = {
    "chat.postMessage": "chat:write",
    "reactions.add": "reactions:write",
    "conversations.replies": "channels:history",
    "conversations.history": "channels:history",  # scripts/alert-roundtrip이 리포트 관찰에 쓴다
    "auth.test": None,
}
# 메서드 호출이 아니라 이벤트 수신에 필요한 스코프 / 이벤트 → 스코프
EVENT_SCOPE = {"app_mention": "app_mentions:read"}
NAMESPACES = ("chat|conversations|reactions|auth|users|files|views|team|bots|pins|search|im|mpim|groups|channels|emoji|dnd|"
              "reminders|usergroups|oauth|apps|admin|bookmarks|calls|dialog|rtm|stars|workflows")
METHOD_RE = re.compile(r'["/]((?:%s)\.[a-zA-Z]+(?:\.[a-zA-Z]+)?)\b' % NAMESPACES)
PLACEHOLDER_HOST_SUFFIX = ".example.com"  # 실제 터널 주소(ngrok 등)를 커밋하지 않게 한다


def source_files():
    for base, dirs, files in os.walk(os.path.join(ROOT, "src", "main")):
        for f in files:
            if f.endswith(".java"):
                yield os.path.join(base, f)
    scripts = os.path.join(ROOT, "scripts")
    for f in os.listdir(scripts):
        p = os.path.join(scripts, f)
        # iCloud 중복 사본("… 2")과 테스트·캐시는 코드가 아니다
        if os.path.isfile(p) and not f.startswith("test_") and not re.search(r" \d+$", f) and not f.endswith(".pyc"):
            yield p


def used_methods():
    found = {}
    for path in source_files():
        for m in METHOD_RE.findall(open(path, encoding="utf-8", errors="replace").read()):
            found.setdefault(m, set()).add(os.path.relpath(path, ROOT))
    return found


def validate(manifest, methods):
    """오류 문자열 목록. 비어 있으면 통과."""
    errors = []
    unknown = sorted(m for m in methods if m not in METHOD_SCOPE)
    if unknown:
        errors.append(f"표에 없는 Slack 메서드: {unknown}")
    need = {METHOD_SCOPE[m] for m in methods if METHOD_SCOPE.get(m)} | set(EVENT_SCOPE.values())
    have = set(manifest["oauth_config"]["scopes"]["bot"])
    if need - have:
        errors.append(f"Manifest에 없는 필요 스코프: {sorted(need - have)}")
    if have - need:
        errors.append(f"코드가 쓰지 않는 스코프가 선언됨: {sorted(have - need)}")
    events = manifest["settings"]["event_subscriptions"]["bot_events"]
    if sorted(events) != sorted(EVENT_SCOPE):
        errors.append(f"봇 이벤트가 코드의 수신 이벤트와 다르다: {events}")
    url = manifest["settings"]["event_subscriptions"].get("request_url", "")
    host = re.sub(r"^https://([^/]+)/.*$", r"\1", url)
    if not (url.startswith("https://") and url.endswith("/slack/events") and host.endswith(PLACEHOLDER_HOST_SUFFIX)):
        errors.append("request_url은 https://<자리표시자>.example.com/slack/events 여야 한다(실제 터널 주소를 커밋하지 않는다)")
    if manifest["settings"].get("socket_mode_enabled"):
        errors.append("서버는 Events API(HTTP)를 쓴다 — socket_mode_enabled는 false여야 한다")
    return errors


results = []


def check(name, cond, detail=""):
    results.append(bool(cond))
    print(("PASS" if cond else "FAIL"), name, "" if cond else detail)


manifest = json.load(open(MANIFEST, encoding="utf-8"))
methods = used_methods()
print("코드가 부르는 Slack 메서드:", {m: sorted(f) for m, f in sorted(methods.items())})

check("JSON 파싱과 필수 필드", manifest["display_information"]["name"] and manifest["features"]["bot_user"]["display_name"])
check("코드가 부르는 메서드를 모두 표가 알고 있다", not [m for m in methods if m not in METHOD_SCOPE], str(sorted(methods)))
check("추출기가 알려진 메서드를 모두 찾는다(정규식 회귀 방지)", set(METHOD_SCOPE) <= set(methods), str(set(METHOD_SCOPE) - set(methods)))
check("Manifest의 스코프·이벤트·URL 자리표시자가 코드 근거와 일치", validate(manifest, methods) == [], str(validate(manifest, methods)))

# 오류 유도: 틀린 사본은 각각 실패해야 한다
m = copy.deepcopy(manifest); m["oauth_config"]["scopes"]["bot"].remove("reactions:write")
check("스코프를 하나 뺀 사본은 실패", any("필요 스코프" in e for e in validate(m, methods)))
m = copy.deepcopy(manifest); m["oauth_config"]["scopes"]["bot"].append("im:history")
check("코드가 쓰지 않는 스코프를 더한 사본은 실패", any("쓰지 않는" in e for e in validate(m, methods)))
m = copy.deepcopy(manifest); m["settings"]["event_subscriptions"]["bot_events"].append("message.channels")
check("이벤트를 추가한 사본은 실패", any("이벤트" in e for e in validate(m, methods)))
m = copy.deepcopy(manifest); m["settings"]["event_subscriptions"]["request_url"] = "https://abc123.ngrok-free.app/slack/events"
check("실제 터널 주소가 든 사본은 실패", any("request_url" in e for e in validate(m, methods)))
m = copy.deepcopy(manifest); m["settings"]["socket_mode_enabled"] = True
check("소켓 모드를 켠 사본은 실패", any("socket_mode" in e for e in validate(m, methods)))
check("코드에 새 Slack 메서드가 생기면 실패", any("표에 없는" in e for e in validate(manifest, {**methods, "users.info": {"x"}})))

print("전체", "PASS" if all(results) else "FAIL", sum(results), "/", len(results))
sys.exit(0 if all(results) else 1)
