# 수신·워커·반응·복구가 같은 이미지를 쓰고 APP_ROLE로 역할을 고른다(PLAN 2단계 M10).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
# 의존성 층을 소스와 분리해 소스만 바뀔 때 다시 받지 않는다.
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon -q bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
