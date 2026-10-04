package com.slack.lab.core.service;

import com.slack.lab.core.model.ReplyFooter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 렌더링된 답글의 "참고 문서" 줄을 형식만 검사한다(P2-3 보조 지표). 답변 <b>내용</b>을 채점하지 않는다 — 출처는 서버가 붙이는
 * 값이라 형식 검사가 안정적이고, 내용 채점은 로컬 모델의 표현 변동에 흔들린다.
 */
public final class AnswerFormatChecker {

    private static final Pattern ITEM = Pattern.compile("^• \\[([^\\]]*)\\] .+$");

    private AnswerFormatChecker() {}

    /** @return 위반 목록. 비어 있으면 형식이 맞다 */
    public static List<String> check(String renderedReply, Set<String> injectedDocumentIds) {
        List<String> violations = new ArrayList<>();
        int header = renderedReply.lastIndexOf("\n\n" + ReplyFooter.REFERENCES_HEADER);
        if (header < 0) {
            if (!injectedDocumentIds.isEmpty()) {
                violations.add("주입한 문서가 있는데 참고 문서 줄이 없다");
            }
            return violations;
        }
        if (injectedDocumentIds.isEmpty()) {
            violations.add("주입한 문서가 없는데 참고 문서 줄이 있다");
        }
        String[] lines = renderedReply.substring(header + 2).split("\n");
        Set<String> seen = new HashSet<>();
        for (int i = 1; i < lines.length; i++) {
            Matcher m = ITEM.matcher(lines[i]);
            if (!m.matches()) {
                violations.add("참고 문서 항목 형식이 아니다: 줄 " + i);
                continue;
            }
            String id = m.group(1);
            if (id.contains("/") || id.contains("\\") || id.contains("..") || id.toLowerCase().endsWith(".md")) {
                violations.add("문서 ID에 경로 조각이 있다: " + id);
            }
            if (!injectedDocumentIds.contains(id)) {
                violations.add("주입하지 않은 문서가 출처로 표시됐다: " + id);
            }
            if (!seen.add(id)) {
                violations.add("같은 문서가 중복 표시됐다: " + id);
            }
        }
        if (seen.isEmpty()) {
            violations.add("참고 문서 헤더만 있고 항목이 없다");
        }
        return violations;
    }
}
