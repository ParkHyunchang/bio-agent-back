# UTF-8 콘솔 출력 (한글 깨짐 방지)
chcp 65001 | Out-Null

$envFile = Join-Path $PSScriptRoot ".env"

if (-not (Test-Path $envFile)) {
    Write-Host ".env 파일이 없습니다. .env 파일을 생성하세요." -ForegroundColor Red
    Write-Host "예시: ANTHROPIC_API_KEY=sk-ant-api03-..." -ForegroundColor Yellow
    exit 1
}

# .env 파일을 읽어 환경변수로 설정
Get-Content $envFile |
  Where-Object { $_ -notmatch '^\s*#' -and $_ -match '=' } |
  ForEach-Object {
    $k, $v = $_ -split '=', 2
    $key = $k.Trim()
    $val = $v.Trim()
    Set-Item "env:$key" $val
    Write-Host "  SET $key" -ForegroundColor DarkGray
  }

Write-Host ""
Write-Host "Spring Boot 시작 중..." -ForegroundColor Green

& "$PSScriptRoot\mvnw.cmd" spring-boot:run
