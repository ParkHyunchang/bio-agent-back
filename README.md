# Bio Agent — Backend

Spring Boot 3 기반 바이오 AI 에이전트 플랫폼 백엔드 API 서버.

## 기술 스택

- **Java 17**
- **Spring Boot 3.2.0**
- **Maven**
- **Lombok**

## 시작하기

### 개발 서버 실행

```powershell
# PowerShell (Windows)
./mvnw.cmd spring-boot:run

# Git Bash / WSL
./mvnw spring-boot:run
```

→ http://localhost:3211

### 빌드

```powershell
# PowerShell (Windows)
./mvnw.cmd clean package

# Git Bash / WSL
./mvnw clean package
```

빌드 결과물은 `target/bio-agent-api-0.0.1-SNAPSHOT.jar`에 생성됩니다.

### JAR 실행

```bash
java -jar target/bio-agent-api-0.0.1-SNAPSHOT.jar
```

## 프로젝트 구조

```
src/main/java/com/hyunchang/bioagent/
├── BioAgentApplication.java       # 애플리케이션 진입점
├── config/
│   └── WebConfig.java             # CORS 설정
└── controller/
    └── HealthController.java      # 헬스체크 엔드포인트
```

## API

| Method | Path | 설명 |
|--------|------|------|
| GET | `/health` | 서버 상태 확인 |

### 응답 예시

```json
GET /health
{
  "status": "ok",
  "service": "bio-agent"
}
```

## 설정

| 항목 | 값 |
|------|----|
| 서버 포트 | `3211` |
| 기본 프로파일 | `local` |

프로파일별 설정 파일:

- `application.yml` — 공통 설정
- `application-local.yml` — 로컬 개발용 (H2 인메모리 DB)
