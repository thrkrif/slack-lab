"""scripts/env-check 검증(합성 env 파일, 외부 호출 없음). 실행: python3 scripts/test_env_check.py (실패하면 종료 코드 1)"""
import os, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "env-check")
ROOT = os.path.dirname(HERE)
SECRETS = ["fx-signing-AAA111", "fx-bot-BBB222", "fx-pg-CCC333", "fx-alert-DDD444"]  # 출력에 나오면 안 되는 픽스처 값

BASE = {
    "SLACK_SIGNING_SECRET": SECRETS[0], "SLACK_BOT_TOKEN": SECRETS[1], "POSTGRES_PASSWORD": SECRETS[2],
    "LLM_MODEL": "m", "SLACK_TEST_CHANNEL": "CFIXTURE",
}
results = []


def env_text(overrides=None, drop=()):
    d = {**BASE, **(overrides or {})}
    for k in drop:
        d.pop(k, None)
    return "".join(f"{k}={v}\n" for k, v in d.items())


def run(text, *args):
    with tempfile.NamedTemporaryFile("w", suffix=".env", delete=False, encoding="utf-8") as f:
        f.write(text)
        path = f.name
    try:
        p = subprocess.run([sys.executable, SCRIPT, path, *args], capture_output=True, text=True)
        return p.returncode, p.stdout + p.stderr
    finally:
        os.unlink(path)


def check(name, cond, detail=""):
    results.append(bool(cond))
    print(("PASS" if cond else "FAIL"), name, "" if cond else detail)


def ok(name, text, *args):
    rc, out = run(text, *args)
    check(name, rc == 0 and not any(s in out for s in SECRETS), f"rc={rc} {out}")


def bad(name, text, key, *args):
    rc, out = run(text, *args)
    check(name, rc == 1 and f"오류 {key}:" in out and not any(s in out for s in SECRETS), f"rc={rc} {out}")


RAG = {"RAG_ENABLED": "true", "RAG_EMBEDDING_MODEL": "bge-m3", "RAG_EMBEDDING_DIMENSION": "1024"}

# 정상
ok("정상 구성 exit 0", env_text())
ok("주석·따옴표·export·빈 줄을 읽는다", "# 주석\n\nexport SLACK_SIGNING_SECRET=\"%s\"\nSLACK_BOT_TOKEN='%s'\n" % (SECRETS[0], SECRETS[1])
   + "".join(f"{k}={v}\n" for k, v in BASE.items() if k not in ("SLACK_SIGNING_SECRET", "SLACK_BOT_TOKEN")))
ok("RAG·알람·분류를 모두 올바르게 켠 구성", env_text({**RAG, "ALERT_SECRET": SECRETS[3], "ALERT_CHANNEL": "CALERT",
                                                    "CLASSIFICATION_ENABLED": "true", "CLASSIFICATION_MODEL": "m"}))

# 오류 유도 3종 (C10)
bad("필수값 누락 → exit 1", env_text(drop=("POSTGRES_PASSWORD",)), "POSTGRES_PASSWORD")
bad("RAG 조합 오류(모델·차원 없음) → exit 1", env_text({"RAG_ENABLED": "true"}), "RAG_EMBEDDING_MODEL")
bad("알람 한쪽만 → exit 1", env_text({"ALERT_SECRET": SECRETS[3]}), "ALERT_CHANNEL")

# 필수값 각각 / 빈 값
for key in ("SLACK_SIGNING_SECRET", "SLACK_BOT_TOKEN", "LLM_MODEL", "SLACK_TEST_CHANNEL"):
    bad(f"{key} 누락", env_text(drop=(key,)), key)
bad("필수값이 빈 값", env_text({"SLACK_BOT_TOKEN": ""}), "SLACK_BOT_TOKEN")
bad("알람 채널만 있고 시크릿 없음", env_text({"ALERT_CHANNEL": "CALERT"}), "ALERT_SECRET")
rc, out = run(env_text({"RAG_ENABLED": "true"}))
check("RAG 오류는 모델·차원 둘 다 알린다", "RAG_EMBEDDING_MODEL" in out and "RAG_EMBEDDING_DIMENSION" in out, out)
bad("차원이 정수가 아님", env_text({**RAG, "RAG_EMBEDDING_DIMENSION": "abc"}), "RAG_EMBEDDING_DIMENSION")
bad("차원이 0", env_text({**RAG, "RAG_EMBEDDING_DIMENSION": "0"}), "RAG_EMBEDDING_DIMENSION")

# 빈 URL, 잘못된 boolean, 분류
bad("LLM_BASE_URL을 명시했지만 빈 값", env_text({"LLM_BASE_URL": ""}), "LLM_BASE_URL")
bad("RAG 켠 채 임베딩 URL이 빈 값", env_text({**RAG, "RAG_EMBEDDING_BASE_URL": ""}), "RAG_EMBEDDING_BASE_URL")
bad("잘못된 boolean", env_text({"RAG_ENABLED": "maybe"}), "RAG_ENABLED")
bad("빈 boolean", env_text({"CLASSIFICATION_ENABLED": ""}), "CLASSIFICATION_ENABLED")
ok("Spring이 받는 boolean 표기(on/yes/1)", env_text({**RAG, "RAG_ENABLED": "ON"}))
bad("분류를 켰는데 모델 없음", env_text({"CLASSIFICATION_ENABLED": "true"}), "CLASSIFICATION_MODEL")
bad("분류를 켠 채 echo 클라이언트", env_text({"CLASSIFICATION_ENABLED": "true", "CLASSIFICATION_MODEL": "m", "LLM_CLIENT": "echo"}), "LLM_CLIENT")

# 호스트 규칙(RagGuard와 같다)
bad("임베딩이 외부 호스트 + 허용 플래그 없음", env_text({**RAG, "RAG_EMBEDDING_BASE_URL": "http://embed.example.com/v1"}), "RAG_EMBEDDING_BASE_URL")
ok("임베딩 외부 호스트 + 허용 플래그", env_text({**RAG, "RAG_EMBEDDING_BASE_URL": "http://embed.example.com/v1", "RAG_ALLOW_EXTERNAL_EMBEDDING": "true"}))
bad("LLM이 외부 호스트 + 허용 플래그 없음", env_text({**RAG, "LLM_BASE_URL": "https://llm.example.com/v1"}), "LLM_BASE_URL")
ok("LLM 외부 호스트 + 허용 플래그", env_text({**RAG, "LLM_BASE_URL": "https://llm.example.com/v1", "RAG_ALLOW_EXTERNAL_LLM": "true"}))
ok("RAG를 끄면 외부 호스트도 검사하지 않음", env_text({"LLM_BASE_URL": "https://llm.example.com/v1"}))
ok("echo 클라이언트는 LLM 호스트를 검사하지 않음", env_text({**RAG, "LLM_BASE_URL": "https://llm.example.com/v1", "LLM_CLIENT": "echo"}))
bad("localhost.evil.com은 localhost가 아니다", env_text({**RAG, "RAG_EMBEDDING_BASE_URL": "http://localhost.evil.com/v1"}), "RAG_EMBEDDING_BASE_URL")
bad("http://localhost@evil.com은 호스트가 evil.com", env_text({**RAG, "RAG_EMBEDDING_BASE_URL": "http://localhost@evil.com/v1"}), "RAG_EMBEDDING_BASE_URL")
bad("http가 아닌 스킴은 허용하지 않음", env_text({**RAG, "RAG_EMBEDDING_BASE_URL": "ftp://localhost/v1"}), "RAG_EMBEDDING_BASE_URL")
ok("host.docker.internal은 기본 허용", env_text({**RAG, "RAG_EMBEDDING_BASE_URL": "http://host.docker.internal:11434/v1"}))
ok("IPv6 루프백도 기본 허용", env_text({**RAG, "RAG_EMBEDDING_BASE_URL": "http://[::1]:11434/v1"}))
# RAG_ALLOWEDHOSTS는 기본 목록을 '대신'한다(합치지 않는다)
bad("RAG_ALLOWEDHOSTS가 기본 localhost를 대체한다", env_text({**RAG, "RAG_ALLOWEDHOSTS": "ollama.corp.internal"}), "RAG_EMBEDDING_BASE_URL")
ok("RAG_ALLOWEDHOSTS에 사내 호스트(와일드카드)",
   env_text({**RAG, "RAG_ALLOWEDHOSTS": "*.corp.internal", "RAG_EMBEDDING_BASE_URL": "http://ollama.corp.internal/v1", "LLM_BASE_URL": "http://llm.corp.internal/v1"}))
bad("와일드카드는 접미사 자체를 허용하지 않음", env_text({**RAG, "RAG_ALLOWEDHOSTS": "*.corp.internal", "RAG_EMBEDDING_BASE_URL": "http://corp.internal/v1", "LLM_BASE_URL": "http://llm.corp.internal/v1"}),
    "RAG_EMBEDDING_BASE_URL")
ok("RAG_ALLOWEDHOSTS에 IPv4 CIDR", env_text({**RAG, "RAG_ALLOWEDHOSTS": "10.0.0.0/8", "RAG_EMBEDDING_BASE_URL": "http://10.1.2.3/v1", "LLM_BASE_URL": "http://10.9.9.9/v1"}))
bad("CIDR 밖 주소", env_text({**RAG, "RAG_ALLOWEDHOSTS": "10.0.0.0/8", "RAG_EMBEDDING_BASE_URL": "http://11.1.2.3/v1", "LLM_BASE_URL": "http://10.9.9.9/v1"}),
    "RAG_EMBEDDING_BASE_URL")

# --index: RAG_DOCS_DIR는 색인을 돌릴 때만 필수
ok("--index 없이는 RAG_DOCS_DIR 불필요", env_text(RAG))
bad("--index인데 RAG_DOCS_DIR 없음", env_text(RAG), "RAG_DOCS_DIR", "--index")
bad("--index인데 디렉터리가 없음", env_text({**RAG, "RAG_DOCS_DIR": "/nonexistent-dir-xyz"}), "RAG_DOCS_DIR", "--index")
bad("--index인데 저장소 안", env_text({**RAG, "RAG_DOCS_DIR": os.path.join(ROOT, "docs")}), "RAG_DOCS_DIR", "--index")
with tempfile.TemporaryDirectory() as outside:
    ok("--index + 저장소 밖 디렉터리", env_text({**RAG, "RAG_DOCS_DIR": outside}), "--index")
bad("RAG를 끈 채 --index", env_text(), "RAG_ENABLED", "--index")

# 파일·사용법
rc, out = subprocess.run([sys.executable, SCRIPT, "/nonexistent.env"], capture_output=True, text=True).returncode, ""
check("env 파일이 없으면 exit 1", rc == 1)
check("알 수 없는 옵션은 exit 2", subprocess.run([sys.executable, SCRIPT, "--bogus"], capture_output=True, text=True).returncode == 2)

# 모든 실패 출력에서 비밀값이 없다는 것을 한 번에: 여러 오류가 겹친 최악의 파일
rc, out = run(env_text({**RAG, "RAG_EMBEDDING_BASE_URL": "http://embed.example.com/v1", "ALERT_SECRET": SECRETS[3], "CLASSIFICATION_ENABLED": "true"}, drop=("LLM_MODEL",)))
check("오류가 겹쳐도 비밀값 출력 0건", rc == 1 and not any(s in out for s in SECRETS) and out.count("오류 ") >= 4, out)

print("전체", "PASS" if all(results) else "FAIL", sum(results), "/", len(results))
sys.exit(0 if all(results) else 1)
