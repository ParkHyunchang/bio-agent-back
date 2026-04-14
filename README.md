# Bio Agent — Backend

Spring Boot 3 기반 바이오 AI 에이전트 플랫폼 백엔드 API 서버.

## 기술 스택

- **Java 17**
- **Spring Boot 3.2.0**
- **Maven**
- **Lombok**

## 시작하기

### 1. 환경변수 설정 (.env 파일)

프로젝트 루트에 `.env` 파일을 생성합니다. (gitignore에 등록되어 있어 커밋되지 않습니다)

```dotenv
# Anthropic Claude API 키
ANTHROPIC_API_KEY=sk-ant-api03-...

# DB 접속 정보 (도커 배포 시 필요)
MYSQL_DATABASE=bio_agent_db
MYSQL_USER=your_user
MYSQL_PASSWORD=your_password
```

### 2. 로컬 개발 서버 실행

`.env`를 자동으로 읽어 환경변수를 주입한 뒤 Spring Boot를 실행합니다.

```powershell
# PowerShell (Windows) — 권장
.\run-local.ps1
```

직접 실행이 필요한 경우:

```powershell
# PowerShell
$env:ANTHROPIC_API_KEY="sk-ant-api03-..."
.\mvnw.cmd spring-boot:run

# Git Bash / WSL
ANTHROPIC_API_KEY=sk-ant-api03-... ./mvnw spring-boot:run
```

→ http://localhost:3211

### 3. 빌드

```powershell
# PowerShell (Windows)
.\mvnw.cmd clean package

# Git Bash / WSL
./mvnw clean package
```

빌드 결과물은 `target/bio-agent-api-0.0.1-SNAPSHOT.jar`에 생성됩니다.

### 4. JAR 직접 실행

```bash
java -jar target/bio-agent-api-0.0.1-SNAPSHOT.jar
```

## 프로젝트 구조

```
src/main/java/com/hyunchang/bioagent/
├── BioAgentApplication.java       # 애플리케이션 진입점
├── config/
│   └── WebConfig.java             # CORS 설정
├── controller/
│   ├── HealthController.java      # 헬스체크 엔드포인트
│   └── PaperController.java       # 논문 리뷰 API
├── service/
│   ├── PubMedService.java         # PubMed E-utilities 연동
│   └── ClaudeService.java         # Anthropic Claude API 연동
└── dto/
    ├── PaperSummary.java          # 논문 검색 결과 DTO
    ├── PaperDetail.java           # 논문 상세 + 초록 DTO
    └── ReviewRequest.java         # AI 리뷰 요청 DTO
```

## API

| Method | Path | 설명 |
|--------|------|------|
| GET | `/health` | 서버 상태 확인 |
| GET | `/api/papers/search?query=...&maxResults=10` | PubMed 논문 검색 |
| GET | `/api/papers/{pmid}` | 논문 상세 및 초록 조회 |
| POST | `/api/papers/review` | Claude AI 논문 요약 생성 |

### 응답 예시

```json
GET /health
{ "status": "ok", "service": "bio-agent" }

GET /api/papers/search?query=BRCA2
[
  {
    "pmid": "37654321",
    "title": "BRCA2 mutations and cancer risk...",
    "authors": ["Kim J", "Lee S", "Park H"],
    "pubDate": "2024 Jan",
    "journal": "Nature Genetics"
  }
]

POST /api/papers/review
Body: { "pmid": "37654321" }
→ { "review": "## 연구 목적\n..." }
```

## 설정

| 항목 | 값 |
|------|----|
| 서버 포트 | `3211` |
| 기본 프로파일 | `local` |

프로파일별 설정 파일:

- `application.yml` — 공통 설정 (환경변수 바인딩)
- `application-local.yml` — 로컬 개발용 (H2 인메모리 DB), gitignore 등록됨
- `application-docker.yml` — 도커 배포용 (MySQL)

## 도커 배포

```bash
# NAS 또는 서버에서
docker compose up -d
```

`.env` 파일이 `docker-compose.yml`과 같은 디렉토리에 있어야 합니다.
