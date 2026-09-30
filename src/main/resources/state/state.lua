-- 2단계 공유 처리 상태 (PLAN 2단계 M11, ARCHITECTURE §3.2·ADR-8)
--
-- Redis Lua는 실행이 원자적이지만 중간 오류를 롤백하지 않는다. 그래서 모든 연산은
--   (1) 검증만 하고 → (2) 멱등 보존 → (3) 상태 기록 → (4) XACKDEL
-- 순서를 지킨다. (2) 뒤에 실패하면 상태가 그대로라 재전달 때 다시 실행되고, (3) 뒤에 실패하면
-- 선점 결과표 2행(COMPLETED 자가치유)·2'행이 보존·정리를 확인하고 마무리한다. 어느 경우에도 입력이
-- 사라지거나, 다른 세대의 보존본이 잘못 지워지지 않는다.
--
-- KEYS: 1 상태 해시 slack:evt:{id}  2 이벤트 스트림  3 보존 해시 slack:preserved:{id}
--       4 DLQ 목록(ZSET)  5 복구 목록(ZSET)  6 재시도 목록(ZSET) slack:retry (M13)
-- ARGV: 1 op  2 event_id  3 fail_after(테스트 전용 실패 주입, 0이면 끔)  4.. op별 인자
-- (claim·mark_sending·renew·finalize·retry는 워커, resolve·reprocess는 recovery CLI(M14)가 부른다)
--
-- 알려진 한계(M11 codex critic 2회전, 의도적으로 남겨둠):
-- - 목록(ZSET) 점수는 preserve() 호출 시각이다. 부분 실패 뒤 재전달로 다시 보존되면 점수가
--   갱신돼 "최초 보존 시각" 정렬이 흔들릴 수 있다. 내용·멱등성에는 영향이 없다.
-- - KEYS[3]·[4]·[5]가 예상과 다른 타입으로 오염되면(외부 조작 등) WRONGTYPE로 스크립트가
--   중단된다 — 그 자체가 안전한 실패이고 위 순서 덕에 재시도로 복구되지만, 쓰기 전 타입을
--   미리 검사하지는 않는다. 운영 중 발견되면 `redis-cli TYPE`으로 원인 키를 확인해 수동 정리한다.

local op, event_id = ARGV[1], ARGV[2]
local fail_after = tonumber(ARGV[3])
local writes = 0

local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

-- 쓰기 명령은 모두 여기를 거친다. 테스트는 N번째 쓰기 뒤에 오류를 내 부분 실패를 재현한다.
local function write(...)
  local r = redis.call(...)
  writes = writes + 1
  if fail_after > 0 and writes >= fail_after then
    error('INJECTED_FAILURE after write ' .. writes)
  end
  return r
end

local function load_state()
  local h = {}
  local flat = redis.call('HGETALL', KEYS[1])
  for i = 1, #flat, 2 do h[flat[i]] = flat[i + 1] end
  return h, #flat > 0
end

local function stream_entry(stream_id)
  if stream_id == nil or stream_id == '' then return nil end
  local r = redis.call('XRANGE', KEYS[2], stream_id, stream_id)
  if #r == 0 then return nil end
  return r[1][2]
end

local function entry_field(entry, name)
  for i = 1, #entry, 2 do
    if entry[i] == name then return entry[i + 1] end
  end
  return nil
end

-- 있는데 event_id 필드가 다른 이벤트 것이면 false — 잘못된 stream_id가 넘어온 것이다.
-- 없거나(이미 소비돼 사라졌으면) event_id 필드 자체가 없으면(구버전 메시지 등) true — 막을 근거가 없다.
local function matches_event(entry)
  if entry == nil then return true end
  local id = entry_field(entry, 'event_id')
  return id == nil or id == event_id
end

-- 멱등: 같은 입력을 다시 써도 결과가 같다. score는 호출 시각(now)이 기본이지만, 재시도는
-- 예약 시각(retry_at)으로 목록에 올려야 스케줄러가 도래한 항목만 골라낼 수 있어 score를 인자로 받는다.
--
-- hset_first(기본 true)로 두 쓰기(보존 HSET·목록 ZADD)의 순서를 고른다. DLQ·복구용 preserve()는 항상
-- HSET을 먼저 쓴다: 그 경로는 이 호출이 끝나면 곧 ACK로 원본 스트림 항목이 사라지므로, 입력을 먼저
-- 안전하게 박아두는 쪽이 우선이다(M11 원칙). 둘 사이에 끊기면(HSET만 됨) ACK도 안 됐으니 재전달이
-- 같은 claim()/finalize() 로직을 그대로 다시 타 preserve()를 멱등하게 재호출하고, 그때 ZADD까지
-- 마친다 — "같은 입력으로 같은 판단을 다시 내린다"는 전제가 성립하기 때문에 안전하다(E24 등으로 검증됨).
--
-- retry op(M13)만 ZADD를 먼저 쓴다(hset_first=false). retry는 이 전제가 깨진다: preserve_at 이후에도
-- 원본 스트림 항목은 ACK되지 않은 채 남아 있고, 그 다음 실행되는 "판단"은 retry op의 재호출이 아니라
-- claim()이 새로 부르는 핸들러다 — 이번엔 성공해서 old_gen으로 COMPLETED될 수도 있다. 그렇게 되면
-- HSET-먼저 순서로는(gen=next_gen만 쓰이고 ZADD가 못 미친 채) 목록 어디에도 없는 보존 해시가 영원히
-- 남는다 — cleanup_preserved_if_same_or_past_gen(old_gen)이 "보존 gen(next_gen) > 완료 gen(old_gen)"을
-- 미래 세대로 오인해 보호하기 때문이다(codex critic 2회전 MAJOR-A). ZADD를 먼저 쓰면 그 실패 지점에서
-- 멈춰도 보존 해시는 아직 없으므로(또는 손대지 않았으므로), 원본이 old_gen으로 정상 완료될 때 같은
-- cleanup이 "보존 없음"으로 판단해 목록 항목까지 함께 지운다 — 입력 유실 위험이 없다(이 시점의 유일한
-- 입력 원본은 아직 ACK 안 된 스트림 항목 자체다). 두 쓰기가 모두 끝난 뒤 끊기면(상태 HSET 전) 기존대로
-- retry_scheduler.lua가 원본 완료를 보고 정리한다.
local function preserve_at(entry, list_key, reason, score, hset_first)
  if hset_first == nil then hset_first = true end
  local function do_hset()
    local args = { 'HSET', KEYS[3] }
    for i = 1, #entry do args[#args + 1] = entry[i] end
    args[#args + 1] = 'preserved_reason'
    args[#args + 1] = reason
    write(unpack(args))
  end
  local function do_zadd()
    write('ZADD', list_key, score, event_id)
  end
  if hset_first then
    do_hset()
    do_zadd()
  else
    do_zadd()
    do_hset()
  end
end

local function preserve(entry, list_key, reason)
  preserve_at(entry, list_key, reason, now, true)
end

-- 보존 해시가 지금 완료되는 세대보다 미래 것이 아닐 때만 지운다. 더 최근 세대가 남긴 보존본을
-- 실수로 걷어내지 않기 위해서다 — 보존 키는 event_id만으로 정해지고 gen을 담지 않으므로, 예컨대
-- gen=0이 정상 완료되는 동안 gen=3짜리 이상 메시지가 1행에서 DLQ로 보존되면 그 내용을 지켜야 한다.
-- 반대로 M14 reprocess처럼 옛 세대(gen=0)의 보존본을 새 세대(gen=1)가 정리하는 경우는 지워야
-- B18(해결된 건의 본문 잔존 0)을 만족한다 — 그래서 "다르면 보존"이 아니라 "미래면 보존"이다.
-- 대상이 없거나(HGET이 false) 세대 이하이면 지우고, 숫자가 아니거나 미래 세대면 남긴다(보수적).
local function cleanup_preserved_if_same_or_past_gen(gen)
  local preserved_gen = redis.call('HGET', KEYS[3], 'gen')
  if preserved_gen ~= false then
    local pg = tonumber(preserved_gen)
    if pg == nil or pg > gen then return end
  end
  write('DEL', KEYS[3])
  write('ZREM', KEYS[4], event_id)
  write('ZREM', KEYS[5], event_id)
  write('ZREM', KEYS[6], event_id)
end

local function ack(group, stream_id)
  if stream_id ~= nil and stream_id ~= '' then
    write('XACKDEL', KEYS[2], group, 'IDS', 1, stream_id)
  end
end

local function list_for(state)
  if state == 'UNKNOWN' then return KEYS[5] end
  return KEYS[4]
end

if op == 'claim' then
  -- ARGV: 4 stream_id 5 group 6 msg_gen 7 new_attempt_id 8 lease_ms 9 window_ms 10 received_at
  --       11 channel 12 thread_ts 13 completed_retention_ms(2행 자가치유용)
  local stream_id, group = ARGV[4], ARGV[5]
  local m = tonumber(ARGV[6])
  local h, exists = load_state()
  local st = h.state
  local s = tonumber(h.gen or '-1')
  local lease_valid = tonumber(h.lease_until or '0') > now

  -- 모든 판정에 앞서 stream_id가 이 이벤트 것인지부터 확인한다. DONE·STALE처럼 보존 없이
  -- ack만 하는 분기도 있어서, 여기서 걸러두지 않으면 다른 이벤트의 대기 중 메시지를 지울 수 있다.
  local raw = stream_entry(stream_id)
  if raw ~= nil and not matches_event(raw) then
    return { 'NO_INPUT' }
  end
  local entry = raw

  -- 1: 상태 gen보다 큰 메시지는 불변식 위반이다(gen은 항상 상태에 먼저 기록된 뒤 투입된다).
  if exists and m > s then
    if entry == nil then return { 'NO_INPUT' } end
    preserve(entry, KEYS[4], 'anomaly_gen')
    ack(group, stream_id)
    return { 'ANOMALY' }
  end
  -- 2: 해결된 건. 사람이 닫은 CLOSED도 여기서 끝나 다시 보존되지 않는다.
  if st == 'COMPLETED' or st == 'CLOSED' then
    -- finalize가 상태 기록 뒤 정리·TTL 설정 전에 중단됐을 수 있다(Lua는 롤백하지 않는다).
    -- TTL이 없는 COMPLETED는 그 흔적이다 — 재확인 때 멱등하게(같은 gen만) 마무리한다.
    if st == 'COMPLETED' and redis.call('TTL', KEYS[1]) == -1 then
      cleanup_preserved_if_same_or_past_gen(s)
      write('PEXPIRE', KEYS[1], tonumber(ARGV[13]))
    end
    ack(group, stream_id)
    return { 'DONE' }
  end
  if st == 'UNKNOWN' or st == 'DEAD' then
    -- 2'/2'': 같은 세대의 재전달만 다시 보존한다. preserve()는 멱등이라 이미 보존된 뒤에
    -- 다시 불러도 안전하다 — 해시만 쓰이고 목록 등록 전에 중단된 부분 실패도 이렇게 복구된다.
    if m == s then
      if entry == nil then return { 'NO_INPUT' } end
      preserve(entry, list_for(st), 'recovered_' .. string.lower(st))
    end
    ack(group, stream_id)
    return { 'DONE' }
  end
  -- 3
  if exists and m < s then
    ack(group, stream_id)
    return { 'STALE' }
  end
  -- 4: 발신했는지 알 수 없다. 재선점하지 않고 결과 불명으로 보낸다(24시간 판정보다 우선).
  if st == 'SENDING' and not lease_valid then
    if entry == nil then return { 'NO_INPUT' } end
    preserve(entry, KEYS[5], 'sending_lease_expired')
    write('HSET', KEYS[1], 'state', 'UNKNOWN', 'stage', 'sending_lease_expired')
    write('PERSIST', KEYS[1])
    ack(group, stream_id)
    return { 'UNKNOWN' }
  end
  -- 4': 사람이 승인한 1회 실행이 소실됐다. 새 승인 없이는 다시 돌리지 않는다.
  if st == 'PROCESSING' and not lease_valid and h.manual_run == '1' then
    if entry == nil then return { 'NO_INPUT' } end
    preserve(entry, KEYS[4], 'manual_attempt_lost')
    write('HSET', KEYS[1], 'state', 'DEAD', 'stage', 'manual_attempt_lost')
    write('PERSIST', KEYS[1])
    ack(group, stream_id)
    return { 'DEAD' }
  end
  -- 5
  if (st == 'PROCESSING' or st == 'SENDING') and lease_valid then
    return { 'BUSY' }
  end
  -- 6
  if st == 'RETRY_WAIT' and tonumber(h.retry_at or '0') > now then
    ack(group, stream_id)
    return { 'STALE' }
  end

  -- 여기부터 실행 가능: 없음 / 임대 만료 PROCESSING / 도래한 RETRY_WAIT
  local first = tonumber(ARGV[10])
  if h.first_received_at then first = math.min(first, tonumber(h.first_received_at)) end
  local gen = exists and s or m
  local manual = h.manual_gen ~= nil and tonumber(h.manual_gen) == m

  -- 7: 자동 실행 허용 기간(최초 수신 후 24시간) 초과
  if now - first > tonumber(ARGV[9]) and not manual then
    if entry == nil then return { 'NO_INPUT' } end
    preserve(entry, KEYS[4], 'window_expired')
    write('HSET', KEYS[1], 'state', 'DEAD', 'stage', 'window_expired', 'gen', gen,
      'first_received_at', first, 'channel', ARGV[11], 'thread_ts', ARGV[12])
    write('PERSIST', KEYS[1])
    ack(group, stream_id)
    return { 'EXPIRED' }
  end

  -- 8: 선점. 승인은 여기서 소비된다.
  write('HSET', KEYS[1], 'state', 'PROCESSING', 'attempt_id', ARGV[7], 'gen', gen,
    'lease_until', now + tonumber(ARGV[8]), 'first_received_at', first,
    'channel', ARGV[11], 'thread_ts', ARGV[12], 'manual_run', manual and '1' or '0')
  if manual then write('HDEL', KEYS[1], 'manual_gen') end
  write('PERSIST', KEYS[1])
  -- retries는 이 claim이 손대지 않는다 — RETRY_WAIT이 남긴 값을 그대로 물려받는다(M13). 새 이벤트는 0이다.
  return { 'CLAIMED', tostring(gen), manual and '1' or '0', tostring(tonumber(h.retries or '0')) }
end

if op == 'mark_sending' then
  -- ARGV: 4 attempt_id. 임대가 유효한 소유자만 SENDING으로 들어갈 수 있다 — 갱신에 실패했으면 발신 금지.
  local h, exists = load_state()
  if not exists or h.attempt_id ~= ARGV[4] or h.state ~= 'PROCESSING'
      or tonumber(h.lease_until or '0') <= now then
    return 0
  end
  write('HSET', KEYS[1], 'state', 'SENDING')
  return 1
end

if op == 'renew' then
  -- ARGV: 4 attempt_id 5 lease_ms. 이미 만료된 임대는 되살리지 않는다(그 사이 다른 시도가 판정됐을 수 있다).
  local h, exists = load_state()
  if not exists or h.attempt_id ~= ARGV[4] or (h.state ~= 'PROCESSING' and h.state ~= 'SENDING')
      or tonumber(h.lease_until or '0') <= now then
    return 0
  end
  write('HSET', KEYS[1], 'lease_until', now + tonumber(ARGV[5]))
  return 1
end

if op == 'finalize' then
  -- ARGV: 4 attempt_id 5 to_state 6 stream_id 7 group 8 dest(''|dlq|recovery) 9 slack_ts 10 kind 11 stage 12 retention_ms
  local aid, to, stream_id, group, dest = ARGV[4], ARGV[5], ARGV[6], ARGV[7], ARGV[8]
  local h, exists = load_state()
  if not exists or h.attempt_id ~= aid then return 0 end
  local from = h.state
  -- 늦은 완료: SENDING 임대 만료로 이미 UNKNOWN이 된 뒤 실제로는 성공했음이 확인된 경우.
  -- 자가_재완료: COMPLETED 기록 뒤 정리·TTL 전에 중단된 것을 같은 시도가 다시 불러 마무리하는 경우
  -- (재전달로는 이 경로에 닿지 않는다 — stream 항목이 이미 없을 수 있어 claim 2행이 못 미친다. 그래서
  -- finalize 자체를 같은 인자로 다시 호출해도 통과시킨다).
  local late_complete = (to == 'COMPLETED' and from == 'UNKNOWN')
  local self_recomplete = (to == 'COMPLETED' and from == 'COMPLETED')
  local allowed = (to == 'COMPLETED' and (from == 'SENDING' or late_complete or self_recomplete))
      or (to == 'UNKNOWN' and from == 'SENDING')
      or (to == 'DEAD' and (from == 'PROCESSING' or from == 'SENDING'))
  if not allowed then return 0 end

  -- 존재하면 이 이벤트 것이어야 한다 — 잘못된 stream_id가 넘어오면 아무것도 쓰지 않고 거절한다.
  -- 늦은 완료·자가_재완료는 stream 항목이 이미 없을 수 있어 entry가 없어도 된다.
  local raw = stream_entry(stream_id)
  if raw ~= nil and not matches_event(raw) then return 0 end
  local entry = nil
  if dest ~= '' then
    if raw == nil then return 0 end
    entry = raw
  end

  if dest ~= '' then
    preserve(entry, dest == 'recovery' and KEYS[5] or KEYS[4], ARGV[11])
  end
  write('HSET', KEYS[1], 'state', to, 'stage', ARGV[11], 'kind', ARGV[10], 'slack_ts', ARGV[9])
  if to == 'COMPLETED' then
    cleanup_preserved_if_same_or_past_gen(tonumber(h.gen or '-1'))
    write('PEXPIRE', KEYS[1], tonumber(ARGV[12]))
  else
    write('PERSIST', KEYS[1])
  end
  ack(group, stream_id)
  return 1
end

if op == 'retry' then
  -- M13. 재시도 가능한 오류를 안내 없이 예약한다: 입력을 재시도 목록에 예약 시각으로 보존 →
  -- RETRY_WAIT(gen+1, retries+1, retry_at) 기록 → XACKDEL. finalize와 같은 검증→멱등쓰기→상태→ACK 순서.
  -- ARGV: 4 attempt_id 5 stream_id 6 group 7 next_gen 8 retry_at 9 retries 10 stage
  local aid, stream_id, group = ARGV[4], ARGV[5], ARGV[6]
  local h, exists = load_state()
  if not exists or h.attempt_id ~= aid or (h.state ~= 'PROCESSING' and h.state ~= 'SENDING') then
    return 0
  end

  local raw = stream_entry(stream_id)
  if raw ~= nil and not matches_event(raw) then return 0 end
  if raw == nil then return 0 end -- 재투입할 입력이 없으면 보존할 수 없다 — 아무것도 쓰지 않고 거절

  -- raw는 옛 세대(gen)를 담고 있다. "옛 gen으로 먼저 보존한 뒤 별도 HSET으로 고쳐 쓰는" 2단계 방식은
  -- 그 사이(보존은 next_gen으로 갱신됐는데 상태 해시는 아직 old_gen인 순간)에 실패하면 보존본·상태
  -- 해시의 gen이 서로 어긋난 채 남는다 — 스케줄러가 이 보존본을 그대로 재투입해 claim()이 ANOMALY로
  -- 오판하고, 완료 후에도 cleanup이 "완료 gen < 보존 gen"이라 청소하지 못해 고아가 남는다(codex REVISE
  -- MAJOR-1). claim·finalize처럼 처음부터 올바른 값으로 한 번에 쓴다: raw를 순회해 gen 필드만
  -- next_gen으로 치환한 배열을 만들어 preserve_at에 넘긴다(단일 HSET).
  --
  -- 이 HSET 자체도 preserve_at의 두 쓰기(HSET·ZADD) 중 하나라 혼자 끊길 수 있다(codex critic 2회전
  -- MAJOR-A) — preserve_at의 hset_first=false로 ZADD를 먼저 써서, HSET만 끊겨도 보존 해시가 남지 않게
  -- 한다(위 preserve_at 정의부 주석에 근거 설명).
  local overridden = {}
  local gen_written = false
  for i = 1, #raw, 2 do
    overridden[#overridden + 1] = raw[i]
    if raw[i] == 'gen' then
      overridden[#overridden + 1] = ARGV[7]
      gen_written = true
    else
      overridden[#overridden + 1] = raw[i + 1]
    end
  end
  if not gen_written then
    overridden[#overridden + 1] = 'gen'
    overridden[#overridden + 1] = ARGV[7]
  end

  preserve_at(overridden, KEYS[6], 'retry_scheduled', tonumber(ARGV[8]), false)
  write('HSET', KEYS[1], 'state', 'RETRY_WAIT', 'gen', ARGV[7], 'retry_at', ARGV[8], 'retries', ARGV[9],
    'stage', ARGV[10])
  write('PERSIST', KEYS[1])
  ack(group, stream_id)
  return 1
end

-- M14 복구 전이. 사람이 recovery CLI로 부르므로 소유자(attempt_id)를 묻지 않고 상태만 본다. 자동 경로는 없다.
-- 결과는 { 상태문자열, ... } 목록이다(claim과 같은 형태).
--
-- resolve: 결과 불명·DLQ 건을 사람이 끝낸다. ARGV: 4 to_state(COMPLETED|CLOSED) 5 slack_ts 6 retention_ms
--   순서: 상태 기록 → 보존 해시·목록 삭제 → TTL. TTL이 마지막인 이유: 중간에 끊기면 "TTL 없는 COMPLETED/CLOSED"가
--   남아 미완임을 알 수 있고, 같은 명령을 다시 실행하면(이미 to_state여도) 정리를 마저 한다.
--   삭제는 무조건이다: 사람이 명시적으로 끝낸 건이라 세대 비교로 보존본을 남길 이유가 없다(B18).
if op == 'resolve' then
  local to, slack_ts = ARGV[4], ARGV[5]
  local h, exists = load_state()
  if not exists then return { 'NOT_FOUND' } end
  local st = h.state
  local rerun = (st == to)
  if not rerun and st ~= 'UNKNOWN' and st ~= 'DEAD' then return { 'BAD_STATE', st or '' } end
  if rerun and to == 'COMPLETED' and (h.slack_ts or '') ~= slack_ts then
    return { 'CONFLICT', h.slack_ts or '' }
  end
  if not rerun then
    if to == 'COMPLETED' then
      write('HSET', KEYS[1], 'state', 'COMPLETED', 'stage', 'manual_resolved', 'slack_ts', slack_ts)
    else
      write('HSET', KEYS[1], 'state', 'CLOSED', 'stage', 'manual_closed')
    end
  end
  write('DEL', KEYS[3])
  write('ZREM', KEYS[4], event_id)
  write('ZREM', KEYS[5], event_id)
  write('ZREM', KEYS[6], event_id)
  write('PEXPIRE', KEYS[1], tonumber(ARGV[6]))
  return { 'OK' }
end

-- reprocess: 미전송이 확인된 건을 사람이 1회 재실행하도록 승인한다. 순서는 claim의 불변식(gen은 상태에 먼저
--   기록된 뒤 투입된다)을 따른다: 상태(RETRY_WAIT, gen+1, manual_gen) → XADD → 목록에서 제거.
--   목록 제거가 마지막이라, 상태만 쓰이고 끊기면 목록에 그대로 남은 채 RETRY_WAIT+manual_gen이 된다 — 이 모양이면
--   같은 명령을 다시 실행할 수 있고(같은 gen으로 XADD), 중복 투입은 선점 결과표(BUSY·STALE·DONE)가 하나만 실행시킨다.
--   보존 해시는 지우지 않는다. 그 승인 실행이 COMPLETED가 될 때 finalize가 지우고, 실패·소실되면 각 종료 경로가
--   다시 DLQ·복구 목록에 올린다(4'행 포함).
if op == 'reprocess' then
  local h, exists = load_state()
  if not exists then return { 'NOT_FOUND' } end
  local st = h.state
  local in_list = redis.call('ZSCORE', KEYS[4], event_id) or redis.call('ZSCORE', KEYS[5], event_id)
  local rerun = (st == 'RETRY_WAIT' and h.manual_gen ~= nil and in_list ~= false)
  if st ~= 'UNKNOWN' and st ~= 'DEAD' and not rerun then return { 'BAD_STATE', st or '' } end
  local flat = redis.call('HGETALL', KEYS[3])
  if #flat == 0 then return { 'NO_PRESERVED' } end

  local s = tonumber(h.gen or '-1')
  local next_gen = rerun and s or (s + 1)
  local args = { 'XADD', KEYS[2], '*' }
  local gen_written = false
  for i = 1, #flat, 2 do
    if flat[i] == 'gen' then
      args[#args + 1] = 'gen'
      args[#args + 1] = tostring(next_gen)
      gen_written = true
    elseif flat[i] ~= 'preserved_reason' then
      args[#args + 1] = flat[i]
      args[#args + 1] = flat[i + 1]
    end
  end
  if not gen_written then
    args[#args + 1] = 'gen'
    args[#args + 1] = tostring(next_gen)
  end

  if not rerun then
    write('HSET', KEYS[1], 'state', 'RETRY_WAIT', 'gen', next_gen, 'retry_at', now, 'manual_gen', next_gen,
      'stage', 'manual_reprocess')
    write('PERSIST', KEYS[1])
  end
  write(unpack(args))
  write('ZREM', KEYS[4], event_id)
  write('ZREM', KEYS[5], event_id)
  return { 'OK', tostring(next_gen) }
end

return redis.error_reply('unknown op ' .. tostring(op))
