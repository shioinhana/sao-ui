$ErrorActionPreference = 'Stop'

$root = 'E:\xy001004\betterdisplay-1.21.11'
$mc = 'C:\Users\Administrator\Desktop\.minecraft'
$verJson = Join-Path $mc 'versions\1.21.11-Forge\1.21.11-Forge.json'
if (-not (Test-Path $verJson)) { throw "missing version json: $verJson" }

$json = Get-Content $verJson -Raw | ConvertFrom-Json
$libs = New-Object System.Collections.Generic.List[string]
foreach ($entry in $json.libraries) {
    if (-not $entry.downloads.artifact.path) { continue }
    $path = Join-Path $mc ('libraries\' + ($entry.downloads.artifact.path -replace '/', '\'))
    if (Test-Path $path) { $libs.Add($path) }
}

# Always include the patched client + Forge universal + eventbus 7
$extra = @(
    (Join-Path $mc 'libraries\net\minecraft\client\1.21.11\client-1.21.11-official.jar'),
    (Join-Path $mc 'libraries\net\minecraftforge\forge\1.21.11-61.0.6\forge-1.21.11-61.0.6-universal.jar'),
    (Join-Path $mc 'libraries\net\minecraftforge\forge\1.21.11-61.0.6\forge-1.21.11-61.0.6-client.jar'),
    (Join-Path $mc 'libraries\net\minecraftforge\eventbus\7.0-beta.12\eventbus-7.0-beta.12.jar')
)
foreach ($e in $extra) { if (Test-Path $e) { $libs.Add($e) } }

$unique = $libs | Select-Object -Unique
$cp = $unique -join ';'
$cpFile = Join-Path $root 'build\classpath.txt'
New-Item -ItemType Directory -Force -Path (Join-Path $root 'build') | Out-Null
Set-Content -Path $cpFile -Value $cp -Encoding ASCII

Write-Host "classpath entries: $($unique.Count)"
Write-Host "written: $cpFile"
