package com.slack.lab.core.port;

import java.util.Optional;

/**
 * 색인 명령의 동시 실행을 막는다. 두 번 동시에 돌면 같은 문서를 번갈아 덮어쓴다. 연결·잠금 수명은 어댑터가 쥐고,
 * 코어는 핸들을 닫을 때 잠금이 풀린다는 계약만 안다.
 */
public interface IndexLock {

    interface Handle extends AutoCloseable {
        @Override
        void close();
    }

    /** 즉시 얻지 못하면(다른 색인이 진행 중) 비어 있다. 기다리지 않는다. */
    Optional<Handle> tryAcquire();
}
