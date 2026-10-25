param(
    [string]$CoreJar,
    [string]$GradleCache,
    [string]$Java,
    [string[]]$CompanionJar = @()
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$properties = @{}
Get-Content -LiteralPath (Join-Path $projectRoot 'gradle.properties') | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') { $properties[$matches[1]] = $matches[2] }
}
if (!$GradleCache) { $GradleCache = Join-Path $projectRoot '.local/gradle-fabric-26.2/caches/modules-2/files-2.1' }
if (!$Java) { $Java = Join-Path $projectRoot '.local/jdk25/bin/java.exe' }
if (!$CoreJar) {
    $CoreJar = Join-Path $projectRoot "build/fabric-26.2/libs/$($properties.modArchiveBaseName)-$($properties.minecraftVersion)-fabric-$($properties.forestryVersion).jar"
}

function Find-Artifact([string]$Group, [string]$Name, [string]$Version) {
    $matches = @(Get-ChildItem -LiteralPath (Join-Path $GradleCache "$Group/$Name/$Version") -Recurse -File -Filter "$Name-$Version.jar")
    if ($matches.Count -ne 1) { throw "Expected exactly one cached $($Group):$($Name):$($Version)" }
    return $matches[0].FullName
}

$loader = Find-Artifact 'net.fabricmc' 'fabric-loader' $properties.fabricLoaderVersion
$fabricApi = Find-Artifact 'net.fabricmc.fabric-api' 'fabric-api' $properties.fabricApiVersion
Add-Type -AssemblyName System.IO.Compression.FileSystem
$loaderArchive = [IO.Compression.ZipFile]::OpenRead($loader)
try {
    $reader = [IO.StreamReader]::new($loaderArchive.GetEntry('fabric-installer.json').Open())
    try { $installer = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
} finally { $loaderArchive.Dispose() }
$asmCoordinate = ($installer.libraries.common | Where-Object { $_.name -like 'org.ow2.asm:asm:*' }).name.Split(':')
$asm = Find-Artifact $asmCoordinate[0] $asmCoordinate[1] $asmCoordinate[2]
$classpath = $loader, $asm -join [IO.Path]::PathSeparator
$probe = Join-Path $PSScriptRoot 'energy-bundling/EnergyBundlingProbe.java'
$output = Join-Path $projectRoot '.local/energy-bundling'
& $Java '-Dfabric.development=false' '-cp' $classpath $probe $CoreJar $fabricApi $output $properties.minecraftVersion $properties.fabricLoaderVersion $properties.energyVersion @CompanionJar
if ($LASTEXITCODE -ne 0) { throw "Energy bundling verification failed ($LASTEXITCODE)" }
