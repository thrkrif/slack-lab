-- M13 재시도 스케줄러. 도래한(retry_at <= now) 항목을 골라 큐에 재투입한다.
--
-- ZRANGEBYSCORE로 도래한 event_id를 고른 뒤 각각 XADD 성공 → ZREM 순서를 지킨다. 반대 순서(ZREM 먼저)면
-- ZREM 뒤 XADD가 실패했을 때 입력이 재시도 목록에서도 스트림에서도 사라져 유실된다. 이 순서라면 두 명령
-- 사이에 실패해도 다음 주기에 같은 event_id가 다시 XADD된다 — 중복 XADD는 안전하다: claim()의 상태
-- 결과표가 같은 세대의 재확인을 BUSY(처리 중)·STALE(이미 지난 세대)·DONE(이미 종료)으로 가로막아
-- 중복 실행을 막는다(state.lua).
--
-- codex critic MAJOR-1: state.lua의 retry op은 "검증→보존(HSET+ZADD)→상태 HSET→ACK" 순서를 지킨다
-- (입력을 먼저 잃지 않는 것이 최우선이라 상태보다 보존이 앞선다, M11 원칙). 그래서 보존(ZADD로 이
-- 목록에 올라옴)이 끝났는데 상태 해시가 아직 RETRY_WAIT·next_gen으로 갱신되기 전인 순간이 항상
-- 존재할 수 있다 — 그 사이에 여기서 상태를 확인하지 않고 그대로 XADD하면, 아직 old_gen인 상태 해시에
-- next_gen 메시지가 들어가 claim()이 gen 불변식 위반(ANOMALY)으로 오판해 DLQ에 잘못 보내고, 그
-- 보존본·DLQ 항목은 나중에 원래 시도가 old_gen으로 정상 완료돼도 청소되지 않는다(완료 gen < 보존 gen
-- 이라 cleanup이 보수적으로 남겨둔다 — 다른 세대의 정당한 보존을 보호하기 위한 의도된 동작이다).
-- 그래서 XADD 전에 상태 해시를 확인한다: RETRY_WAIT로 확정된 것만 재투입하고, 아직 확정 전(원래 시도가
-- 여전히 PROCESSING·SENDING 중 — retry() 호출이 상태 기록에 이르기 전에 끊겼을 수 있다)이면 이번 주기는
-- 건너뛰고 목록에 남겨 다음 주기에 다시 확인한다. 이미 다른 경로로 끝난(재전달로 원래 시도가 그대로
-- 완료·소멸한) 경우는 재투입할 이유가 없으니 목록에서만 지운다.
--
-- KEYS: 1 재시도 목록(ZSET) slack:retry  2 이벤트 스트림
-- ARGV: 1 fail_after(테스트 전용 실패 주입, 0이면 끔)  2 limit(한 주기 최대 처리 건수)  3 보존 해시 접두사
--       4 상태 해시 접두사

local fail_after = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
local preserved_prefix = ARGV[3]
local state_prefix = ARGV[4]
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
  local state = redis.call('HGET', state_prefix .. event_id, 'state')

  if state == 'RETRY_WAIT' then
    local hkey = preserved_prefix .. event_id
    local flat = redis.call('HGETALL', hkey)
    if #flat > 0 then
      write('XADD', KEYS[2], '*', unpack(flat))
      write('ZREM', KEYS[1], event_id)
      requeued = requeued + 1
    else
      -- 보존된 입력이 없다(이례적 — 수동 정리 등). 재투입할 것이 없으니 목록에서만 지운다.
      write('ZREM', KEYS[1], event_id)
    end
  elseif state == 'PROCESSING' or state == 'SENDING' then
    -- retry() 호출이 상태 기록 전에 끊겼거나(재시도 예약이 아직 확정되지 않음), 원래 시도가 아직
    -- 진행 중이다 — 지금 재투입하면 gen 불변식이 깨질 수 있으니 건너뛰고 다음 주기에 다시 본다.
  else
    -- 이미 다른 경로로 끝났다(재전달로 원래 시도가 완료·소멸·CLOSED 등) — 재투입할 이유가 없다.
    write('ZREM', KEYS[1], event_id)
    -- 보존 해시가 아직 이 재시도(우리가 방금 부분 실패로 남긴 것)의 것이면(다른 op이 그 뒤 덮어쓰지
    -- 않았으면) 함께 지운다 — 그렇지 않으면 어느 목록에도 없이(DLQ·복구·재시도 전부 아님) 해시만 영영
    -- 남는다(B18). preserved_reason으로 소유권을 확인한다: DLQ·복구용으로 이미 덮어써졌다면 그건 다른
    -- 목적의 보존이므로 건드리지 않는다.
    local hkey = preserved_prefix .. event_id
    if redis.call('HGET', hkey, 'preserved_reason') == 'retry_scheduled' then
      write('DEL', hkey)
    end
  end
end
return requeued
