"""M9 Ollama 동시성 타당성 (PLAN 2단계 M9, PRD §5 환경 고정).

앱과 같은 요청 형식(시스템 프롬프트·max_tokens=512·temperature=0.3·keep_alive=30m)으로
고정 질문 10개를 동시에 보내는 배치를 반복한다. Ollama는 P0 기록 환경(기본 설정) 그대로 둔다.
배치 완료 시간은 큐 대기를 포함한 "답변 시간"의 LLM 몫 상한을 가늠하는 값이다.

재현: python3 docs/spikes/ollama_concurrency_spike.py qwen2.5:7b 3
"""
import json
import math
import sys
import threading
import time
import urllib.request

BASE = "http://localhost:11434/v1/chat/completions"
SYSTEM_PROMPT = ("너는 한국어로만 답하는 챗봇이다. 어떤 경우에도 중국어·영어·다른 언어 단어를 섞지 마라. "
                 "모든 문장을 한국어로만 작성하라. 간결하게 500자 이내로 답하라.")
QUESTIONS = [
    "커넥션 풀이 고갈됐을 때 먼저 확인할 것은?",
    "배포 직후 5xx가 늘면 어떻게 대응하나?",
    "디스크가 가득 찼다는 알림이 왔을 때 1차 대응은?",
    "레디스 메모리가 급증하면 무엇을 봐야 하나?",
    "API 지연이 갑자기 늘었을 때 원인을 좁히는 순서는?",
    "DB 슬로우 쿼리가 폭증하면 어떻게 하나?",
    "외부 결제 API가 타임아웃을 내면 어떻게 대응하나?",
    "OOM으로 파드가 재시작되면 무엇을 확인하나?",
    "인증서 만료 알림을 받으면 무엇부터 하나?",
    "메시지 큐 적체가 쌓이면 어떻게 대응하나?",
]


def call(model, q, out, i):
    body = json.dumps({
        "model": model,
        "messages": [{"role": "system", "content": SYSTEM_PROMPT}, {"role": "user", "content": q}],
        "max_tokens": 512, "keep_alive": "30m", "temperature": 0.3,
    }).encode()
    req = urllib.request.Request(BASE, body, {"Content-Type": "application/json"})
    t = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            data = json.load(r)
        tokens = data.get("usage", {}).get("completion_tokens")
        out[i] = ((time.perf_counter() - t), tokens, None)
    except Exception as e:  # 실패도 표본으로 남긴다 — 제외하고 합격시키지 않는다
        out[i] = ((time.perf_counter() - t), None, repr(e))


def batch(model):
    out = [None] * len(QUESTIONS)
    ths = [threading.Thread(target=call, args=(model, q, out, i)) for i, q in enumerate(QUESTIONS)]
    for th in ths:
        th.start()
    for th in ths:
        th.join()
    return out


def main():
    model = sys.argv[1]
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 3
    # 워밍업 1회(콜드 스타트는 별도 기록)
    t = time.perf_counter()
    call(model, "안녕", [None], 0)
    print(f"warmup {time.perf_counter() - t:.1f}s")
    all_lat = []
    for b in range(n):
        res = batch(model)
        lats = [r[0] for r in res]
        errs = [r[2] for r in res if r[2]]
        toks = [r[1] for r in res if r[1]]
        all_lat += lats
        print(f"batch {b + 1}: min={min(lats):.1f}s max={max(lats):.1f}s errors={len(errs)} "
              f"avg_tokens={sum(toks) / max(len(toks), 1):.0f}")
    s = sorted(all_lat)
    print(f"ALL n={len(s)} p50={s[len(s) // 2]:.1f}s p95={s[math.ceil(0.95 * len(s)) - 1]:.1f}s max={s[-1]:.1f}s")


if __name__ == "__main__":
    main()
