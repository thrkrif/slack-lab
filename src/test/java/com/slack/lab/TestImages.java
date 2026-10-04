package com.slack.lab;

import org.testcontainers.utility.DockerImageName;

/** 테스트 컨테이너 이미지의 단일 출처. V3 마이그레이션이 pgvector extension을 만들어서 모든 Postgres 테스트가 같은 이미지를 쓴다. */
public final class TestImages {

    public static final DockerImageName POSTGRES = DockerImageName.parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres");

    private TestImages() {}
}
