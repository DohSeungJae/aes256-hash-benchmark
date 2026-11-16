# ── 1단계: 빌드 (호스트에 JDK/Maven이 없어도 컨테이너 안에서 jar를 만든다) ──
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# 의존성 레이어를 먼저 받아 두어 소스만 바뀌었을 때 재빌드를 빠르게 한다
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

# ── 2단계: 실행 ──
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

COPY --from=build /workspace/target/ahbench.jar /app/ahbench.jar

EXPOSE 8080

# JAVA_OPTS로 힙 크기 등을 바꿀 수 있다 (측정 시 모든 구성에서 동일하게 고정 — 계획서 5장)
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/ahbench.jar"]
