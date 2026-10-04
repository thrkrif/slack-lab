package com.slack.lab.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AnswerFormatCheckerTest {

    static final String OK = "답변\n\n*참고 문서*\n• [DB-001] 커넥션 풀\n• [OPS-001] 디스크";

    @Test
    void 주입한_문서만_한_번씩_표시했으면_형식이_맞다() {
        assertThat(AnswerFormatChecker.check(OK, Set.of("DB-001", "OPS-001"))).isEmpty();
        assertThat(AnswerFormatChecker.check("답변만", Set.of())).as("주입 0건이면 줄이 없어야 한다").isEmpty();
    }

    @Test
    void 주입했는데_줄이_없거나_주입이_없는데_줄이_있으면_위반이다() {
        assertThat(AnswerFormatChecker.check("답변만", Set.of("DB-001"))).hasSize(1);
        assertThat(AnswerFormatChecker.check(OK, Set.of())).anyMatch(v -> v.contains("주입한 문서가 없는데"));
    }

    @Test
    void 주입하지_않은_문서_중복_경로_조각은_위반이다() {
        assertThat(AnswerFormatChecker.check(OK, Set.of("DB-001"))).anyMatch(v -> v.contains("주입하지 않은 문서: OPS-001".replace("문서: ", "문서가 출처로 표시됐다: ")));
        assertThat(AnswerFormatChecker.check("a\n\n*참고 문서*\n• [A] x\n• [A] y", Set.of("A"))).anyMatch(v -> v.contains("중복"));
        assertThat(AnswerFormatChecker.check("a\n\n*참고 문서*\n• [team/db/x] 제목", Set.of("team/db/x"))).anyMatch(v -> v.contains("경로"));
        assertThat(AnswerFormatChecker.check("a\n\n*참고 문서*\n• [x.md] 제목", Set.of("x.md"))).anyMatch(v -> v.contains("경로"));
    }

    @Test
    void 항목_형식이_아니거나_항목이_없으면_위반이다() {
        assertThat(AnswerFormatChecker.check("a\n\n*참고 문서*\n- DB-001 제목", Set.of("DB-001"))).isNotEmpty();
        assertThat(AnswerFormatChecker.check("a\n\n*참고 문서*", Set.of("DB-001"))).anyMatch(v -> v.contains("항목이 없다"));
    }
}
