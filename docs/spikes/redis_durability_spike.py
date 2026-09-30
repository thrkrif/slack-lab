"""M9 Redis 스파이크 (PLAN 2단계 M9). 의존성 없이 RESP 소켓으로 직접 호출한다.

측정: XADD + WAITAOF 1 0 <ms> 왕복 지연 p95, WAITAOF numlocal 반환값, XACKDEL 단일 명령 동작.
재현: docker run -d --name slab-spike-redis -p 6390:6379 redis:8.2-alpine \
        redis-server --appendonly yes --appendfsync always
      python3 docs/spikes/redis_durability_spike.py 6390
"""
import math
import socket
import sys
import time


class Resp:
    def __init__(self, port):
        self.sock = socket.create_connection(("127.0.0.1", port))
        self.buf = b""

    def call(self, *args):
        out = [b"*%d\r\n" % len(args)]
        for a in args:
            a = str(a).encode()
            out.append(b"$%d\r\n%s\r\n" % (len(a), a))
        self.sock.sendall(b"".join(out))
        return self._read()

    def _line(self):
        while b"\r\n" not in self.buf:
            self.buf += self.sock.recv(65536)
        line, self.buf = self.buf.split(b"\r\n", 1)
        return line

    def _read(self):
        line = self._line()
        t, rest = line[:1], line[1:]
        if t in (b"+", b"-"):
            return rest.decode() if t == b"+" else RuntimeError(rest.decode())
        if t == b":":
            return int(rest)
        if t == b"$":
            n = int(rest)
            if n < 0:
                return None
            while len(self.buf) < n + 2:
                self.buf += self.sock.recv(65536)
            val, self.buf = self.buf[:n], self.buf[n + 2:]
            return val.decode()
        if t == b"*":
            n = int(rest)
            return None if n < 0 else [self._read() for _ in range(n)]
        raise ValueError(line)


def p95(xs):
    xs = sorted(xs)
    return xs[math.ceil(0.95 * len(xs)) - 1]


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 6390
    r = Resp(port)
    print("appendfsync =", r.call("CONFIG", "GET", "appendfsync"))
    r.call("DEL", "spike:events")
    r.call("XGROUP", "CREATE", "spike:events", "workers", "$", "MKSTREAM")

    samples, numlocals = [], set()
    for i in range(200):
        t = time.perf_counter()
        r.call("XADD", "spike:events", "*", "event_id", f"E{i}", "text", "x" * 200)
        res = r.call("WAITAOF", 1, 0, 150)
        samples.append((time.perf_counter() - t) * 1000)
        numlocals.add(res[0])
    print(f"XADD+WAITAOF n=200 p50={sorted(samples)[99]:.2f}ms p95={p95(samples):.2f}ms max={max(samples):.2f}ms numlocal={numlocals}")

    # XACKDEL: 읽은 항목을 ACK와 동시에 삭제하는지(단일 명령) 확인
    entries = r.call("XREADGROUP", "GROUP", "workers", "c1", "COUNT", 1, "STREAMS", "spike:events", ">")
    eid = entries[0][1][0][0]
    before = r.call("XLEN", "spike:events")
    res = r.call("XACKDEL", "spike:events", "workers", "IDS", 1, eid)
    after = r.call("XLEN", "spike:events")
    pending = r.call("XPENDING", "spike:events", "workers")[0]
    print(f"XACKDEL result={res} xlen {before}->{after} pending={pending}")

    # 크래시 내구성 확인용 마커 — 호출자가 docker kill 후 재기동해 XRANGE로 확인한다
    marker = r.call("XADD", "spike:events", "*", "event_id", "DURABILITY-MARKER")
    print("WAITAOF", r.call("WAITAOF", 1, 0, 150), "marker", marker)


if __name__ == "__main__":
    main()
