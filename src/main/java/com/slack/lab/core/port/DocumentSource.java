package com.slack.lab.core.port;

import com.slack.lab.core.model.PortResult;
import com.slack.lab.core.model.SourceDocument;
import java.util.List;

/**
 * 색인할 문서의 출처. 첫 구현체는 저장소 밖 로컬 마크다운 디렉터리이고, 위키·Slack 스레드 같은 출처는 어댑터 추가로
 * 붙인다. 출처가 통째로 비어 보이는 사고(경로 설정 실수)를 삭제 감지가 오해하지 않도록 실패는 빈 목록이 아니라
 * {@link PortResult.Failed}로 돌려준다.
 */
public interface DocumentSource {

    PortResult<List<SourceDocument>> list();
}
