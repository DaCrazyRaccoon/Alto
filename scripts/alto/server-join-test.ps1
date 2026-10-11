# This file is part of Baritone (Alto fork). LGPL-3.0, see LICENSE.
#
# DEV-ONLY: proof that Alto is purely client-side.
#   1. starts a NeoForge dedicated server WITHOUT Alto (offline mode, bound to 127.0.0.1:25599) from the dev
#      toolchain, in testenv/run-server (the EULA was accepted by the user for this local test server only);
#   2. joins it with the Alto dev test client (harness mode "join") and runs a few Alto commands there;
#   3. stops both and writes testenv/server-join-check.txt.
#
#   pwsh scripts/alto/server-join-test.ps1
param(
    [int] $TimeoutMinutes = 15
)
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).ProviderPath
$testenvCandidates = @((Join-Path $source '..\testenv'), (Join-Path $source '..\..\testenv'))
$testenv = (Resolve-Path ($testenvCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1)).ProviderPath
$serverDir = Join-Path $testenv 'run-server'
$result = Join-Path $testenv 'server-join-check.txt'
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = Join-Path $HOME '.jdks\jdk-21.0.12.1+1' }
New-Item -ItemType Directory -Force (Join-Path $serverDir 'logs') | Out-Null
Set-Content -Encoding ascii (Join-Path $serverDir 'eula.txt') 'eula=true'
@(
    'online-mode=false',
    'server-ip=127.0.0.1',
    'server-port=25599',
    'level-type=minecraft\:flat',
    'generate-structures=false',
    'difficulty=peaceful',
    'spawn-protection=0',
    'max-players=2',
    'view-distance=6',
    'simulation-distance=6',
    'enable-command-block=false',
    'motd=Alto client-only test (no Alto on this server)'
) | Set-Content -Encoding ascii (Join-Path $serverDir 'server.properties')
# A fresh test world each run (only this test server's own world in testenv).
$world = Join-Path $serverDir 'world'
if (Test-Path $world) { Remove-Item -Recurse -Force $world }
$serverLog = Join-Path $serverDir 'logs\latest.log'
if (Test-Path $serverLog) { Move-Item -Force $serverLog (Join-Path $serverDir 'logs\previous-run.log') }

$started = Get-Date
$gradleLog = Join-Path $testenv 'server-join-gradle.log'
$gradle = Start-Process -FilePath 'cmd.exe' -ArgumentList "/c `"`"$source\gradlew.bat`" runBareServer --console=plain > `"$gradleLog`" 2>&1`"" `
    -WorkingDirectory $source -WindowStyle Hidden -PassThru
Write-Output "[server-join] server starting (gradle pid $($gradle.Id))"

function Get-TestServer {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
        $_.CommandLine -like '*bareServerRunVmArgs*' -and $_.CommandLine -like "*$([regex]::Escape($source).Replace('\\','\'))*"
    }
}

$verdict = 'FAIL'
$lines = @()
try {
    $deadline = (Get-Date).AddMinutes(6)
    while ((Get-Date) -lt $deadline) {
        if ((Test-Path $serverLog) -and (Select-String -Path $serverLog -Pattern 'Done \(' -Quiet)) { break }
        if ($gradle.HasExited) { throw 'the server process ended before it was ready (see server-join-gradle.log)' }
        Start-Sleep -Seconds 2
    }
    if (-not ((Test-Path $serverLog) -and (Select-String -Path $serverLog -Pattern 'Done \(' -Quiet))) { throw 'server not ready after 6 minutes' }
    # The server's mod list line for Alto would read "Baritone (Alto fork) <version> (baritoe)".
    $altoOnServer = Select-String -Path $serverLog -Pattern '\(baritoe\)\s*$' -Quiet
    $lines += "server ready after $([int]((Get-Date) - $started).TotalSeconds) s; Alto loaded on the server: $altoOnServer"
    if ($altoOnServer) { throw 'the server loaded Alto; this test needs a server without it' }

    $client = & pwsh -File (Join-Path $PSScriptRoot 'test-client.ps1') -Run client -Mode join `
        -Commands 'alto status;plan minecraft:oak_planks 4;alto integrations;script list' -TimeoutMinutes $TimeoutMinutes 2>&1
    $lines += $client
    if (($client -join "`n") -match '(?m)^PASS ') { $verdict = 'PASS' }
    $joined = Select-String -Path $serverLog -Pattern 'joined the game' -Quiet
    $kicked = Select-String -Path $serverLog -Pattern 'lost connection: (?!Disconnected)' -Quiet
    $lines += "server log: player joined $joined, unexpected disconnect $kicked"
    if (-not $joined -or $kicked) { $verdict = 'FAIL' }
} catch {
    $lines += "ERROR: $_"
} finally {
    foreach ($p in @(Get-TestServer)) {
        Stop-Process -Id $p.ProcessId -Force -Confirm:$false -ErrorAction SilentlyContinue
        $lines += "stopped test server pid $($p.ProcessId)"
    }
    if (-not $gradle.HasExited) { Stop-Process -Id $gradle.Id -Force -Confirm:$false -ErrorAction SilentlyContinue }
}
$summary = "$verdict  server-join  ($(Get-Date -Format s))"
@($summary) + $lines | Set-Content -Encoding utf8 $result
Write-Output $summary
$lines | ForEach-Object { Write-Output $_ }
if ($verdict -ne 'PASS') { exit 1 }
