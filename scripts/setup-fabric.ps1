param([switch]$Offline)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$jdkRoot = Join-Path $repoRoot '.local\jdk25'
if (-not (Test-Path -LiteralPath (Join-Path $jdkRoot 'bin\javac.exe'))) {
    if ($Offline) { throw 'Local JDK 25 is missing. Run setup-fabric.ps1 once without -Offline.' }
    # Reuse the checksum-pinned Temurin installer; do not overwrite JDK 21 or an existing JDK 25.
    & (Join-Path $PSScriptRoot 'setup-local.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'Local JDK setup failed.' }
}
$release = Get-Content -LiteralPath (Join-Path $jdkRoot 'release') -Raw
if ($release -notmatch 'JAVA_VERSION="25\.0\.4\.1"' -or $release -notmatch 'IMPLEMENTOR="Eclipse Adoptium"') {
    throw 'Expected the project-local Eclipse Temurin 25.0.4.1 toolchain.'
}
$gradleArguments = @('assemble', 'gametestClasses', 'checkFabricF3', '--console=plain')
if ($Offline) { $gradleArguments += '--offline' }
& (Join-Path $repoRoot 'gradlew-fabric.bat') @gradleArguments
if ($LASTEXITCODE -ne 0) { throw 'Fabric setup failed. Review the Gradle error above before starting Minecraft.' }
Write-Host 'Forestry CE - Refabricated F3 built. Client: .\gradlew-fabric.bat runClient; server: .\gradlew-fabric.bat runServer'
Write-Host 'Jade and JEI are optional integrations. See docs/porting/f3-status.zh-CN.md for validation status.'
