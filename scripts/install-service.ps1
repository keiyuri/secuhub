<#
.SYNOPSIS
    securance-app을 Windows 서비스로 설치한다 (WinSW 사용, 계획서 7.1절)
.DESCRIPTION
    레거시 SR_Speed_Server가 Windows 서비스(ServiceBase)로 운영되던 것과 동일한 방식을 유지한다.
    WinSW 실행 파일은 라이선스/배포 정책상 이 저장소에 포함하지 않으므로 사전에 직접 받아야 한다.
.NOTES
    사전 준비:
      1. https://github.com/winsw/winsw/releases 에서 WinSW-x64.exe를 내려받아
         scripts/winsw/securance-app-service.exe 로 저장
      2. ./scripts/build.ps1 실행해 securance-app.jar을 빌드
      3. securance-app.jar을 scripts/winsw/ 디렉터리로 복사(또는 xml의 workingdirectory 조정)
    관리자 권한 PowerShell에서 실행해야 한다(서비스 설치는 관리자 권한 필요).
#>
param(
    [ValidateSet("install", "uninstall", "start", "stop", "status")]
    [string]$Action = "install"
)

$ErrorActionPreference = "Stop"
$winswDir = Join-Path $PSScriptRoot "winsw"
$winswExe = Join-Path $winswDir "securance-app-service.exe"

if (-not (Test-Path $winswExe)) {
    Write-Error "WinSW 실행 파일이 없습니다: $winswExe`n" `
        + "https://github.com/winsw/winsw/releases 에서 내려받아 이 이름으로 저장한 뒤 다시 실행하세요."
    exit 1
}

Write-Host "securance-app 서비스 $Action 실행 중..." -ForegroundColor Cyan
& $winswExe $Action

if ($LASTEXITCODE -ne 0) {
    Write-Error "WinSW $Action 실패 (exit code $LASTEXITCODE)"
    exit $LASTEXITCODE
}

Write-Host "완료. services.msc 에서 'securance-app (secuhub)' 서비스를 확인하세요." -ForegroundColor Green
