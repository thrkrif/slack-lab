-- M13 재시도 스케줄러. 도래한(retry_at <= now) 항목을 골라 큐에 재투입한다.
--
-- ZRANGEBYSCORE로 도래한 event_id를 고른 뒤 각각 XADD 성공 → ZREM 순서를 지킨다. 반대 순서(ZREM 먼저)면
-- ZREM 뒤 XADD가 실패했을 때 입력이 재시도 목록에서도 스트림에서도 사라져 유실된다. 이 순서라면 두 명령
-- 사이에 실패해도 다음 주기에 같은 event_id가 다시 XADD된다 — 중복 XADD는 안전하다: claim()의 상태
-- 결과표가 같은 세대의 재확인을 BUSY(처리 중)·STALE(이미 지난 세대)·DONE(이미 종료)으로 가로막아
-- 중복 실행을 막는다(state.lua).
--
-- KEYS: 1 재시도 목록(ZSET) slack:retry  2 이벤트 스트림
-- ARGV: 1 fail_after(테스트 전용 실패 주입, 0이면 끔)  2 limit(한 주기 최대 처리 건수)  3 보존 해시 접두사

local fail_after = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
local prefix = ARGV[3]
local writes = 0

local function write(...)
  local r = redis.call(...)
  writes = writes + 1
  if fail_after > 0 and writes >= fail_after then
    error('INJECTED_FAILURE after write ' .. writes)
  end
  return r
end

local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', now, 'LIMIT', 0, limit)
local requeued = 0
for i = 1, #due do
  local event_id = due[i]
  local hkey = prefix .. event_id
  local flat = redis.call('HGETALL', hkey)
  if #flat > 0 then
    write('XADD', KEYS[2], '*', unpack(flat))
    write('ZREM', KEYS[1], event_id)
    requeued = requeued + 1
  else
    -- 보존된 입력이 없다(이례적 — 수동 정리 등). 재투입할 것이 없으니 목록에서만 지운다.
    write('ZREM', KEYS[1], event_id)
  end
end
return requeued
