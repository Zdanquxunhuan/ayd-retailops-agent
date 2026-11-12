param(
    [ValidateSet('test','build','run','evaluate','investigate')][string]$Action = 'test',
    [string]$JdkHome = 'F:\ayd\jdk-17',
    [ValidateSet('stub','remote','qwen')][string]$Provider = 'stub',
    [ValidateRange(1,20)][int]$Repeats = 1,
    [ValidateRange(1,10000)][int]$MaxRequests = 10,
    [string]$Cases = '',
    [string]$Fixture = 'investigation-reversal',
    [ValidateSet('CHECK_STATUS','DIAGNOSE_CAUSE')][string]$Intent = 'DIAGNOSE_CAUSE'
)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$javaExecutable = Join-Path $JdkHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExecutable)) { throw "JDK not found: $JdkHome" }
$env:JAVA_HOME = $JdkHome
Push-Location $projectDirectory
try {
    if ($Action -eq 'test' -or $Action -eq 'build') {
        $goal = if ($Action -eq 'test') { 'test' } else { 'package' }
        & .\mvnw.cmd "-Dmaven.repo.local=$projectDirectory\.build-cache\repository" -B -ntp $goal
    } else {
        $jar = Join-Path $projectDirectory 'target\ayd-retailops-agent-0.0.1-SNAPSHOT.jar'
        if (-not (Test-Path -LiteralPath $jar)) { throw 'Run scripts/stage1.ps1 build first.' }
        $javaArguments = @('-jar', $jar, "--omniops.model.provider=$Provider", "--omniops.model.max-requests=$MaxRequests")
        if ($Action -eq 'evaluate') {
            $javaArguments += @('--spring.main.web-application-type=none', '--omniops.evaluate=true',
                '--omniops.data-dir=./data/evaluation-tasks', "--omniops.evaluation-repeats=$Repeats", "--omniops.evaluation-cases=$Cases")
        }
        if ($Action -eq 'investigate') {
            $javaArguments += @('--spring.main.web-application-type=none', '--omniops.investigate=true',
                '--omniops.data-dir=./data/investigation-tasks', "--omniops.investigation-fixture=$Fixture", "--omniops.investigation-intent=$Intent")
        }
        & $javaExecutable @javaArguments
    }
    if ($LASTEXITCODE -ne 0) { throw "Command failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }
