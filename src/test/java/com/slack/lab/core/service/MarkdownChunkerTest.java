package com.slack.lab.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MarkdownChunkerTest {

    @Test
    void 짧은_문단들은_한도_안에서_묶인다() {
        var chunker = new MarkdownChunker(100, 10);

        var chunks = chunker.chunk("첫째 문단입니다.\n\n둘째 문단입니다.\n\n셋째 문단입니다.");

        assertThat(chunks).containsExactly("첫째 문단입니다.\n\n둘째 문단입니다.\n\n셋째 문단입니다.");
    }

    @Test
    void 한도를_넘으면_문단_경계에서_나눈다() {
        var chunker = new MarkdownChunker(100, 10);
        String a = "a".repeat(60);
        String b = "b".repeat(60);

        assertThat(chunker.chunk(a + "\n\n" + b)).containsExactly(a, b);
    }

    @Test
    void 한도를_넘는_문단은_겹쳐_자르고_모든_글자가_어느_청크엔가_들어간다() {
        var chunker = new MarkdownChunker(100, 20);
        String p = "0123456789".repeat(25); // 250자

        var chunks = chunker.chunk(p);

        assertThat(chunks).hasSize(3);
        assertThat(chunks).allMatch(c -> c.length() <= 100);
        assertThat(chunks.get(0).substring(80)).isEqualTo(chunks.get(1).substring(0, 20)); // 겹침
        assertThat(chunks.get(2)).endsWith(p.substring(p.length() - 10));
    }

    @Test
    void 빈_내용과_공백뿐인_내용은_청크가_없다() {
        var chunker = new MarkdownChunker(100, 10);

        assertThat(chunker.chunk("")).isEmpty();
        assertThat(chunker.chunk("\n\n  \n\n\t\n")).isEmpty();
    }

    @Test
    void CRLF도_문단으로_나눈다() {
        var chunker = new MarkdownChunker(100, 10);
        String a = "a".repeat(70);

        assertThat(chunker.chunk(a + "\r\n\r\n" + a)).hasSize(2);
    }

    @Test
    void 잘못된_설정은_거부한다() {
        assertThatThrownBy(() -> new MarkdownChunker(50, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MarkdownChunker(200, 200)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MarkdownChunker(200, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
