-- 작업 처리 상태 저장소 (ADR-9, M20). Redis 구현(state.lua)의 선점 결과표를 같은 의미로 옮긴다.
-- 모든 시각은 epoch ms(bigint)이고 "지금"은 항상 DB 시계다(Redis TIME과 같은 이유: 여러 프로세스의 시계 편차를 피한다).

CREATE TABLE processing_state (
    event_id         text    PRIMARY KEY,
    -- PROCESSING | SENDING | COMPLETED | UNKNOWN | DEAD | RETRY_WAIT | CLOSED
    state            text    NOT NULL,
    attempt_id       text,
    gen              bigint  NOT NULL DEFAULT 0,
    lease_until      bigint  NOT NULL DEFAULT 0,
    first_received_at bigint NOT NULL,
    channel          text,
    thread_ts        text,
    manual_run       boolean NOT NULL DEFAULT false,
    manual_gen       bigint,
    retries          integer NOT NULL DEFAULT 0,
    retry_at         bigint,
    stage            text,
    kind             text,
    slack_ts         text,
    -- 이번 시도의 입력(JSON). 결과 불명·DLQ·재시도처럼 입력을 보존해야 하는 전이가 쓴다.
    input            text,
    -- 완료·닫힘 보존 기한. NULL이면 만료되지 않는다(미해결 건은 자동 삭제하지 않는다, PRD §5).
    expires_at       bigint,
    updated_at       bigint  NOT NULL
);

CREATE INDEX processing_state_expires_idx ON processing_state (expires_at) WHERE expires_at IS NOT NULL;

-- 보존된 입력. 한 이벤트는 한 목록에만 있다(Redis는 해시 하나에 목록 멤버십이 따로여서 옛 목록에 남는 틈이 있었다).
CREATE TABLE preserved_input (
    event_id      text   PRIMARY KEY,
    list_name     text   NOT NULL CHECK (list_name IN ('dlq', 'recovery', 'retry')),
    reason        text   NOT NULL,
    gen           bigint NOT NULL,
    -- retry: 재투입 시각, dlq·recovery: 보존 시각
    due_at        bigint NOT NULL,
    payload       text   NOT NULL,
    preserved_at  bigint NOT NULL
);

CREATE INDEX preserved_input_list_idx ON preserved_input (list_name, due_at);
