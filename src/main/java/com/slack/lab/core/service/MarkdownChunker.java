package com.slack.lab.core.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 문단 단위로 묶어 {@code maxChars} 안에 담는 청커. 문단 하나가 한도를 넘으면 {@code overlap}만큼 겹쳐 잘라낸다(겹침은
 * 이 경우에만 쓴다). 토큰이 아니라 글자 수 기준이라 모델별 토큰 한도를 직접 모른다 — 한도는 문맥 4K에서 K개를 넣을 수 있게
 * 설정으로 조정한다(PLAN M28·M30).
 */
public final class MarkdownChunker {

    private final int maxChars;
    private final int overlap;

    public MarkdownChunker(int maxChars, int overlap) {
        if (maxChars < 100 || overlap < 0 || overlap >= maxChars) {
            throw new IllegalArgumentException("chunk-size >= 100, 0 <= chunk-overlap < chunk-size 이어야 한다");
        }
        this.maxChars = maxChars;
        this.overlap = overlap;
    }

    public List<String> chunk(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String raw : text.replace("\r\n", "\n").split("\n{2,}")) {
            String p = raw.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (p.length() > maxChars) {
                flush(buf, out);
                hardSplit(p, out);
            } else if (buf.length() + 2 + p.length() <= maxChars && buf.length() > 0) {
                buf.append("\n\n").append(p);
            } else {
                flush(buf, out);
                buf.append(p);
            }
        }
        flush(buf, out);
        return out;
    }

    private void hardSplit(String p, List<String> out) {
        int step = maxChars - overlap;
        for (int start = 0; start < p.length(); start += step) {
            int end = Math.min(p.length(), start + maxChars);
            out.add(p.substring(start, end));
            if (end == p.length()) {
                break;
            }
        }
    }

    private static void flush(StringBuilder buf, List<String> out) {
        if (buf.length() > 0) {
            out.add(buf.toString());
            buf.setLength(0);
        }
    }
}
