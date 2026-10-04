package com.slack.lab;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * 의존 방향을 빌드에서 강제한다(ARCHITECTURE ADR-9, PLAN M19). 코어({@code core})는 인터페이스(포트)만 알고 구현체
 * (어댑터)와 인프라 라이브러리를 모른다. 이 규칙이 깨지면 큐·저장소·LLM 구현체를 바꿀 때 코어까지 고쳐야 한다.
 */
@AnalyzeClasses(packages = "com.slack.lab", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule 코어는_어댑터를_모른다 = noClasses().that().resideInAPackage("com.slack.lab.core..")
            .should().dependOnClassesThat().resideInAPackage("com.slack.lab.adapter..");

    // 규칙 2(핸들러는 HTTP를 모른다)를 코어 전체로 넓힌 것이다. Spring DI 어노테이션(@Component)과 설정 속성은 허용한다.
    @ArchTest
    static final ArchRule 코어는_인프라_라이브러리를_모른다 = noClasses().that().resideInAPackage("com.slack.lab.core..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.data..", "io.lettuce..", "jakarta.servlet..", "org.springframework.http..",
                    "org.springframework.web..", "java.net.http..", "com.fasterxml.jackson..", "com.rabbitmq..",
                    "org.postgresql..", "java.sql..", "javax.sql..", "org.springframework.jdbc..", "org.springframework.amqp..",
                    "org.springframework.jms..", "org.apache.kafka..", "software.amazon.awssdk..", "javax.net..", "redis.clients..",
                    "org.apache.hc..", "okhttp3..", "jakarta.persistence..", "org.hibernate..", "io.netty..", "com.pgvector..")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.net.HttpURLConnection")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.net.URL")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.net.Socket");

    // 코어가 config를 아무 클래스나 참조하면 설정 클래스에 어댑터 지식이 들어와도 모른다. 코어가 실제로 쓰는 설정 값과 역할 표시만
    // 이름으로 허용한다(QueueProperties·ReactionProperties처럼 브로커 전용 설정은 코어가 쓰면 안 된다).
    @ArchTest
    static final ArchRule 코어는_설정_값만_참조한다 = noClasses().that().resideInAPackage("com.slack.lab.core..")
            .should().dependOnClassesThat(resideInAPackage("com.slack.lab.config")
                    .and(not(simpleName("ProcessingProperties"))).and(not(simpleName("ExperimentProperties")))
                    .and(not(simpleName("LlmProperties"))).and(not(simpleName("SlackProperties")))
                    .and(not(simpleName("StateProperties"))).and(not(simpleName("RetryProperties")))
                    .and(not(simpleName("WorkerProperties"))).and(not(simpleName("ContextProperties"))).and(not(simpleName("RagProperties")))
                    .and(not(simpleName("AppRole")))
                    .and(not(simpleName("ConditionalOnRole")))
                    .and(not(simpleName("OnRoleCondition"))));

    @ArchTest
    static final ArchRule 모델은_JDK와_모델만_쓴다 = classes().that().resideInAPackage("com.slack.lab.core.model..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "com.slack.lab.core.model..");

    @ArchTest
    static final ArchRule 포트는_모델만_안다 = classes().that().resideInAPackage("com.slack.lab.core.port..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "com.slack.lab.core.model..",
                    "com.slack.lab.core.port..");

    @ArchTest
    static final ArchRule 어댑터끼리는_서로를_모른다 = slices().matching("com.slack.lab.adapter.(*)..").should()
            .notDependOnEachOther();
}
