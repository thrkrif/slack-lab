package com.slack.lab.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.slack.lab.core.model.ClassifyQuestion;
import com.slack.lab.core.model.RequestKind;
import com.slack.lab.core.service.InjectionBaseline.Row;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

// C3 기준선 규칙(final 주입 문항 15개 이상이어야 합격선에 포함, 검색 불가는 판단 불가)을 경계로 고정한다.
class InjectionBaselineTest {

    /** final 40문항(단순 20 + 정보 부족 20). 앞의 unavailable개는 검색 불가, 그 뒤 injected개는 문서 주입. */
    private static List<Row> finalRows(int injected, int unavailable) {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            RequestKind k = i < 20 ? RequestKind.SIMPLE : RequestKind.NEEDS_INFO;
            var q = new ClassifyQuestion("Q" + i, ClassifyQuestion.FINAL, k, "q" + i);
            boolean un = i < unavailable;
            boolean inj = !un && i < unavailable + injected;
            rows.add(new Row(q, inj ? List.of("DB-001") : List.of(), un));
        }
        return rows;
    }

    @Test
    void 주입_15문항은_포함_14문항은_제외() {
        assertThat(InjectionBaseline.c3Included(finalRows(15, 0))).isTrue();
        assertThat(InjectionBaseline.c3Included(finalRows(14, 0))).isFalse();
    }

    @Test
    void 검색_불가가_있으면_판단하지_않는다() {
        assertThat(InjectionBaseline.c3Included(finalRows(30, 1))).isFalse();
        assertThat(InjectionBaseline.summary(finalRows(30, 1))).contains("판단 불가");
    }

    @Test
    void 요약에_종류별_주입_건수와_판정이_들어간다() {
        String s = InjectionBaseline.summary(finalRows(15, 0));
        assertThat(s).contains("[final] 단순+정보 부족 40문항 중 문서 주입 15문항").contains("SIMPLE: 15/20")
                .contains("NEEDS_INFO: 0/20").contains("C3을 합격선에 포함");
    }
}
