param(
    [ValidateSet('play', 'play-live', 'sample', 'login', 'profiles', 'models', 'review', 'logout', 'help', 'benchmark-export', 'benchmark-run', 'deck-plan', 'sequence-run')]
    [string]$Action = 'help',
    [ValidateSet('chatgpt', 'openrouter')][string]$Provider = 'chatgpt',
    [string]$Model,
    [ValidateSet('none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max')][string]$ReasoningEffort,
    [int]$MaxOutputTokens = 8192,
    [string]$InputFile,
    [string]$OutputFile,
    [string]$DataDir,
    [string]$Profile,
    [switch]$NewAccount,
    [int]$TimeoutSeconds = 60,
    [string]$JavaHome
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
if (-not $DataDir) { $DataDir = Join-Path $repo '.forge-llm' }
if ($Action -in @('review', 'benchmark-run', 'sequence-run', 'deck-plan') -and $Provider -eq 'chatgpt' -and $PSBoundParameters.ContainsKey('MaxOutputTokens')) {
    throw 'ChatGPT plan usage does not support -MaxOutputTokens. Use -TimeoutSeconds and ChatGPT usage controls.'
}
$DataDir = [IO.Path]::GetFullPath($DataDir)
if (-not $JavaHome) {
    $bundledJdk = Get-ChildItem (Join-Path (Split-Path -Parent $repo) 'toolchain') -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($bundledJdk) { $JavaHome = $bundledJdk.FullName } else { $JavaHome = $env:JAVA_HOME }
}
if ($JavaHome) { $java = Join-Path $JavaHome 'bin\java.exe' } else { $java = (Get-Command java.exe).Source }
if (-not (Test-Path -LiteralPath $java)) { throw 'Java 17 is required; set -JavaHome to its folder.' }
$jar = Get-ChildItem (Join-Path $repo 'forge-gui-desktop\target') -Filter '*-jar-with-dependencies.jar' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) { throw 'Build the Forge desktop JAR before running the advisor.' }

# Isolate this prototype's Forge profile, credentials and captures in an ignored local directory.
$previousAppData = $env:APPDATA
$previousLocalAppData = $env:LOCALAPPDATA
$env:APPDATA = Join-Path $DataDir 'profile'
$env:LOCALAPPDATA = $env:APPDATA
New-Item -ItemType Directory -Force -Path (Join-Path $env:APPDATA 'Forge') | Out-Null
$connections = Join-Path $DataDir 'connections'
if ($Action -eq 'play-live') {
    $demoDeckDestination = Join-Path $env:APPDATA 'Forge\decks\constructed'
    New-Item -ItemType Directory -Force -Path $demoDeckDestination | Out-Null
    Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'llm-demo-decks') -Filter '*.dck' | ForEach-Object {
        $demoDeckPath = Join-Path $demoDeckDestination $_.Name
        if (-not (Test-Path -LiteralPath $demoDeckPath)) { Copy-Item -LiteralPath $_.FullName -Destination $demoDeckPath }
    }
    if (-not $Model) { $Model = 'gpt-6-luna' }
    if (-not $ReasoningEffort) { $ReasoningEffort = 'high' }
    $arguments = @("-Dforge.llm.dataDir=$DataDir", "-Dforge.llm.provider=$Provider", "-Dforge.llm.model=$Model",
        "-Dforge.llm.reasoningEffort=$ReasoningEffort", "-Dforge.llm.timeoutSeconds=$TimeoutSeconds")
    if ($Profile) { $arguments += "-Dforge.llm.profile=$Profile" }
    $arguments += @('-cp', $jar.FullName, 'forge.llm.LivePlayLauncher')
} elseif ($Action -eq 'play') {
    $captureDir = Join-Path $DataDir 'captures'
    $arguments = @("-Dforge.llm.captureDir=$captureDir", '-cp', $jar.FullName, 'forge.view.Main')
} else {
    $arguments = @('-cp', $jar.FullName, 'forge.llm.LlmAdvisorCli', $Action, '--data-dir', $connections)
    if ($Action -in @('models', 'review', 'benchmark-run', 'sequence-run', 'deck-plan')) { $arguments += @('--provider', $Provider) }
    if ($Model) { $arguments += @('--model', $Model) }
    if ($InputFile) { $arguments += @('--input', [IO.Path]::GetFullPath($InputFile)) }
    elseif ($Action -eq 'benchmark-run') { $arguments += @('--input', (Join-Path $DataDir 'benchmark-suite\manifest.json')) }
    if ($OutputFile) { $arguments += @('--output', [IO.Path]::GetFullPath($OutputFile)) }
    elseif ($Action -eq 'sample') { $arguments += @('--output', (Join-Path $DataDir 'stage-depths.json')) }
    elseif ($Action -eq 'benchmark-export') { $arguments += @('--output', (Join-Path $DataDir 'benchmark-suite')) }
    elseif ($Action -eq 'benchmark-run') { $arguments += @('--output', (Join-Path $DataDir 'benchmark-results')) }
    elseif ($Action -eq 'sequence-run') { $arguments += @('--output', (Join-Path $DataDir 'turn-sequences')) }
    elseif ($Action -eq 'deck-plan') { $arguments += @('--output', (Join-Path $DataDir 'deck-strategy.json')) }
    if ($Profile) { $arguments += @('--profile', $Profile) }
    if ($NewAccount) { $arguments += '--new-account' }
    if ($Action -in @('review', 'benchmark-run', 'sequence-run', 'deck-plan')) {
        $arguments += @('--timeout-seconds', $TimeoutSeconds.ToString())
        if ($Provider -eq 'openrouter') { $arguments += @('--max-output-tokens', $MaxOutputTokens.ToString()) }
        if ($ReasoningEffort) { $arguments += @('--reasoning-effort', $ReasoningEffort) }
    }
}
if ($Action -in @('play', 'play-live')) {
    # Keep the Java 17 desktop launch consistent with Forge's packaged Windows launcher.
    [xml]$desktopPom = Get-Content -Raw -LiteralPath (Join-Path $repo 'forge-gui-desktop\pom.xml')
    $desktopJavaOptions = ($desktopPom.project.properties.'mandatory.java.args' + ' ' + $desktopPom.project.properties.'addopen.java.args') -split '\s+'
    $arguments = @($desktopJavaOptions | Where-Object { $_ }) + $arguments
}
Push-Location (Join-Path $repo 'forge-gui')
try {
    & $java @arguments
    $advisorExit = $LASTEXITCODE
} finally {
    Pop-Location
    $env:APPDATA = $previousAppData
    $env:LOCALAPPDATA = $previousLocalAppData
}
exit $advisorExit
