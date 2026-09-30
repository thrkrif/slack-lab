-- M15 발행. 처리용 스트림과 반응용 스트림에 한 스크립트로 XADD한다. 다른 명령이 두 XADD 사이에 끼어들 수 없다
-- (Lua는 롤백하지 않는다: 두 번째가 오류로 실패하면 첫 번째만 남고, 그 경우 호출자가 503을 내 Slack 재전송이
-- 온다 — 이벤트는 중복되지만 선점 결과표가 하나만 실행시키고 반응 누락은 재전송이 메운다). WAITAOF는 차단 명령이라 Lua 안에서 쓸 수 없어, 이 스크립트가 끝난 뒤
-- 호출자가 한 번만 보낸다.
--
-- KEYS: 1 이벤트 스트림  2 반응 스트림
-- ARGV: 1 이벤트 필드 인자 수(필드/값 쌍의 2배)  2.. 이벤트 필드/값들, 이어서 반응 필드/값들
-- 반환: 이벤트 스트림 항목 ID
local n = tonumber(ARGV[1])
local ev = {}
for i = 2, n + 1 do ev[#ev + 1] = ARGV[i] end
local rx = {}
for i = n + 2, #ARGV do rx[#rx + 1] = ARGV[i] end
local id = redis.call('XADD', KEYS[1], '*', unpack(ev))
redis.call('XADD', KEYS[2], '*', unpack(rx))
return id
