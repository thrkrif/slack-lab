package com.slack.lab.core.model;

/**
 * 임베딩이 붙은 청크. {@code index}는 문서 안 순서(0부터)다. 배열은 방어적으로 복사하며 동등성은 참조 기준이므로 값
 * 비교가 필요하면 {@link java.util.Arrays#equals(float[], float[])}를 쓴다.
 */
public record DocumentChunk(int index, String text, float[] embedding) {

    public DocumentChunk {
        embedding = embedding.clone();
    }

    @Override
    public float[] embedding() {
        return embedding.clone();
    }
}
