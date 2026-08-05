<#
.SYNOPSIS
    로컬 개발 환경에서 securance-app을 기동한다 (Windows 네이티브, Docker 미사용 — 계획서 7.1절)
.DESCRIPTION
    application-local.yml(.gitignore 대상, application-local.yml.example를 복사해 준비)을 사용해
    `local` 프로필로 부트런한다. MariaDB는 Windows용 설치본이나 기존 개발 서버(예: 192.168.0.91)에
    직접 연결하며, RabbitMQ/Redis는 기본 비활성(enabled=false)이라 로컬 실행에 필요 없다.
.EXAMPLE
    ./scripts/run-local.ps1
#>
param(
    [string]$Profile = "local"
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot\..

$localConfig = ".\securance-app\src\main\resources\application-$Profile.yml"
$exampleConfig = ".\securance-app\src\main\resources\application-local.yml.example"

if (-not (Test-Path $localConfig) -and $Profile -eq "local") {
    Write-Warning "application-local.yml이 없습니다. $exampleConfig 를 복사해 DB 접속 정보를 채워주세요."
    Write-Host "  Copy-Item '$exampleConfig' '$localConfig'" -ForegroundColor Yellow
    exit 1
}

& .\gradlew.bat ":securance-app:bootRun" "--args=--spring.profiles.active=$Profile"
