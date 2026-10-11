# This file is part of Baritone (Alto fork). LGPL-3.0, see LICENSE.
#
# DEV-ONLY: start a test client, decide PASS/FAIL, always close the test client afterwards.
#
#   pwsh scripts/alto/test-client.ps1 -Run packClient -Mode boot
#   pwsh scripts/alto/test-client.ps1 -Run packClient -Mode world -Commands "selftest all" -TimeoutMinutes 30
#   pwsh scripts/alto/test-client.ps1 -Run client -Mode world            # Baritone only (no Create/EMI)
#
# FAIL if any of these happens (checked every 2 seconds):
#   - the game log shows a fatal loading problem (missing dependency, pre-loading error, crash report, ...),
#     which also covers failures BEFORE any mod is constructed (the in-game harness cannot see those);
#   - the in-game harness reports "LAUNCH FAILED" (error screen seen) or "TIMEOUT";
#   - the client exits without writing a report;
#   - the time limit passes.
# PASS only when the harness writes "BOOT OK" / "WORLD OK".
#
# It only ever stops processes it can prove are this project's test clients: the PID the harness wrote,
# or a java process launched by a Gradle daemon whose command line carries -Dalto.testclient.
# The result is written to testenv/launch-check-<Run>.txt next to the run folder.

param(
    [ValidateSet('client', 'packClient')] [string] $Run = 'packClient',
    [ValidateSet('boot', 'world')] [string] $Mode = 'boot',
    [string] $Commands = '',
    [int] $TimeoutMinutes = 20,
    [string] $GameMode = 'survival',
    # Extra Gradle arguments, e.g. "-Palto_pack_exclude=connector-*.jar" (negative test of this check)
    [string] $ExtraGradleArgs = ''
)

$ErrorActionPreference = 'Stop'
$source = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).ProviderPath
$testenvCandidates = @((Join-Path $source '..\testenv'), (Join-Path $source '..\..\testenv'))
$testenv = (Resolve-Path ($testenvCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1)).ProviderPath
$gameDir = if ($Run -eq 'packClient') { Join-Path $testenv 'run-pack' } else { Join-Path $testenv 'run-dev' }
$task = if ($Run -eq 'packClient') { 'preparePackRun runPackClient' } else { 'prepareDevRun runClient' }
$resultFile = Join-Path $testenv "launch-check-$Run.txt"
$gradleLog = Join-Path $testenv "launch-check-$Run-gradle.log"
$report = Join-Path $gameDir 'alto-devtest-report.txt'
$pidFile = Join-Path $gameDir 'alto-devtest.pid'
$latest = Join-Path $gameDir 'logs\latest.log'

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = Join-Path $HOME '.jdks\jdk-21.0.12.1+1' }

# Fresh state: old report, pid file and log must not be mistaken for this run.
New-Item -ItemType Directory -Force (Join-Path $gameDir 'logs') | Out-Null
Remove-Item -Force -ErrorAction SilentlyContinue $report, $pidFile
if (Test-Path $latest) { Move-Item -Force $latest (Join-Path $gameDir 'logs\previous-run.log') }

$fatalPatterns = @(
    'Error during pre-loading phase',
    'Missing or unsupported mandatory dependencies',
    'Crash report saved to',
    'has failed to load correctly',
    'ModLoadingException',
    'Exception in thread "Render thread"',
    'Encountered an unexpected exception',
    '\[alto-devtest\] LAUNCH FAILED',
    '\[alto-devtest\] STUCK on screen'
)

function Get-TestClientProcesses([datetime] $since) {
    $found = @()
    if (Test-Path $pidFile) {
        $p = Get-Process -Id ([int](Get-Content $pidFile -Raw)) -ErrorAction SilentlyContinue
        if ($p) { $found += $p.Id }
    }
    # A dev client is a java process started by a Gradle daemon AFTER this check began, whose main class is
    # Minecraft's (or which carries our marker). Nothing else is ever stopped.
    $daemons = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -match 'GradleDaemon' } | Select-Object -ExpandProperty ProcessId
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $daemons -contains $_.ParentProcessId -and $_.CreationDate -ge $since -and
            ($_.CommandLine -match 'alto\.testclient' -or $_.CommandLine -match 'net\.minecraft\.client\.main\.Main') } |
        ForEach-Object { $found += $_.ProcessId }
    return $found | Select-Object -Unique
}

$start = Get-Date
Write-Host "[test-client] $Run / $Mode starting ($task), game dir $gameDir"
$gradleArgs = "/c `"`"$source\gradlew.bat`" $task -Palto_devtest=$Mode -Palto_devtest_gamemode=$GameMode -Palto_devtest_timeout=$($TimeoutMinutes * 60) --console=plain"
if ($Commands) { $gradleArgs += " `"-Palto_devtest_commands=$Commands`"" }
if ($ExtraGradleArgs) { $gradleArgs += " $ExtraGradleArgs" }
$gradle = Start-Process -FilePath 'cmd.exe' -ArgumentList "$gradleArgs > `"$gradleLog`" 2>&1`"" -WorkingDirectory $source -PassThru -WindowStyle Hidden

$verdict = $null
$reasons = @()
while (-not $verdict) {
    Start-Sleep -Seconds 2
    if (Test-Path $report) {
        $lines = Get-Content $report
        $last = $lines[-1]
        if ($last -match '^(BOOT OK|WORLD OK)') { $verdict = 'PASS' } else { $verdict = 'FAIL' }
        $reasons += "harness: $last"
        break
    }
    if (Test-Path $latest) {
        $hits = Select-String -Path $latest -Pattern $fatalPatterns -Context 0, 3 | Select-Object -First 6
        if ($hits) {
            $verdict = 'FAIL'
            foreach ($h in $hits) {
                $reasons += "log line $($h.LineNumber): $($h.Line.Trim())"
                foreach ($c in $h.Context.PostContext) { $reasons += "    $($c.Trim())" }
            }
            break
        }
    }
    if ($gradle.HasExited) {
        Start-Sleep -Seconds 3
        if (Test-Path $report) { continue }
        $verdict = 'FAIL'
        $reasons += "client exited without a report (gradle exit code $($gradle.ExitCode)); see $gradleLog"
        break
    }
    if ((Get-Date) -gt $start.AddMinutes($TimeoutMinutes + 2)) {
        $verdict = 'FAIL'
        $reasons += "no result after $TimeoutMinutes minutes"
        break
    }
}

# Always close the test client (and only the test client).
Start-Sleep -Seconds 3
foreach ($id in Get-TestClientProcesses $start) {
    Write-Host "[test-client] stopping test client pid $id"
    Stop-Process -Id $id -Force -Confirm:$false -ErrorAction SilentlyContinue
}
if (-not $gradle.HasExited) { $gradle.WaitForExit(60000) | Out-Null }
Start-Sleep -Seconds 2
$stillRunning = Get-TestClientProcesses $start
if ($stillRunning) { $reasons += "WARNING: test client still running (pid $($stillRunning -join ', '))" }
else { $reasons += 'test client closed' }

$elapsed = [int]((Get-Date) - $start).TotalSeconds
$out = @("$verdict  $Run/$Mode  after $elapsed s  ($(Get-Date -Format s))") + $reasons
if (Test-Path $report) { $out += '--- harness report ---'; $out += Get-Content $report }
Set-Content -Path $resultFile -Value $out -Encoding utf8
$out | ForEach-Object { Write-Host $_ }
if ($verdict -eq 'PASS') { exit 0 } else { exit 1 }
