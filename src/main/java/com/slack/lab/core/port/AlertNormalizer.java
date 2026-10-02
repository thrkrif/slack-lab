package com.slack.lab.core.port;

import com.slack.lab.core.model.AlertEvent;
import java.util.Map;
import java.util.Optional;

/**
 * 알람 원천(CloudWatch/SNS, Grafana 등)이 보낸 원본 요청을 {@link AlertEvent}로 바꾼다. 원천마다 어댑터가 하나씩 있다(M23).
 * 인증(시크릿 헤더, SNS 서명)은 HTTP 어댑터의 몫이고 이 포트는 검증을 통과한 본문만 받는다. 아직 구현체가 없다.
 */
public interface AlertNormalizer {

    /** 이 정규화기가 맡는 원천 이름(예: {@code cloudwatch}). 요청 경로 선택에 쓴다. */
    String source();

    /** 처리 대상이 아니면(해결 알림 등) {@code Optional.empty()}. */
    Optional<AlertEvent> normalize(Map<String, String> headers, byte[] body);
}
