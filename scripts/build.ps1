<#
.SYNOPSIS
    securance-app 빌드 스크립트 (Windows 네이티브, Docker 미사용 — 계획서 7.1절)
.DESCRIPTION
    루트에서 ./gradlew.bat :securance-app:bootJar 를 실행해 단일 실행 가능 JAR을 만든다.
#>
param(
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot\..

if ($SkipTests) {
    & .\gradlew.bat :securance-app:bootJar -x test
} else {
    & .\gradlew.bat :securance-app:bootJar
}

if ($LASTEXITCODE -ne 0) {
    Write-Error "빌드 실패 (exit code $LASTEXITCODE)"
    exit $LASTEXITCODE
}

$jar = Get-ChildItem -Path ".\securance-app\build\libs\securance-app.jar" -ErrorAction SilentlyContinue
if ($jar) {
    Write-Host "빌드 완료: $($jar.FullName) ($([math]::Round($jar.Length / 1MB, 1)) MB)" -ForegroundColor Green
} else {
    Write-Warning "securance-app.jar을 찾을 수 없습니다. build/libs 디렉터리를 확인하세요."
}
