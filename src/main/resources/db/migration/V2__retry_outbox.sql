-- 재시도 발신함 (M21). 재투입을 마지막으로 큐에 반영한 시각. NULL이면 아직 반영하지 않았다.
ALTER TABLE preserved_input ADD COLUMN relayed_at bigint;
