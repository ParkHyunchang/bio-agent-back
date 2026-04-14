# ── 1단계: 빌드 ──────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app

# 의존성 레이어 캐시 (소스 변경 시에도 의존성은 재다운로드 생략)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 소스 복사 후 빌드
COPY src ./src
RUN mvn clean package -DskipTests -B

# ── 2단계: 실행 ──────────────────────────────────────────────
FROM eclipse-temurin:17-jre

WORKDIR /app

# 빌드 결과 JAR 복사
COPY --from=builder /app/target/*.jar app.jar

# 로그 디렉토리 생성
RUN mkdir -p /app/logs

EXPOSE 3211

ENTRYPOINT ["java", "-jar", "app.jar"]
