package com.slack.lab.core.port;

import java.util.Optional;

/**
 * 색인 명령의 동시 실행을 막는다. 두 번 동시에 돌면 같은 문서를 번갈아 덮어쓴다. 연결·잠금 수명은 어댑터가 쥐고,
 * 코어는 핸들을 닫을 때 잠금이 풀린다는 계약만 안다.
 */
public interface IndexLock {

    interface Handle extends AutoCloseable {
        /**
         * 잠금을 아직 쥐고 있는가. 잠금을 쥔 연결이 끊기면(서버 재시작, 강제 종료) 서버가 잠금을 풀어 다른 색인이 시작될 수 있다 —
         * 쓰기 전에 확인해 소유권을 잃은 실행이 다른 실행의 대기 세대를 건드리지 않게 한다. 확인과 쓰기 사이의 짧은 틈까지
         * 막지는 못한다(드문 사고에 대한 방어이지 분산 락 보증이 아니다).
         */
        boolean isHeld();

        @Override
        void close();
    }

    /** 즉시 얻지 못하면(다른 색인이 진행 중) 비어 있다. 기다리지 않는다. */
    Optional<Handle> tryAcquire();
}
