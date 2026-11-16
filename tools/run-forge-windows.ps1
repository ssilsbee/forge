# Launch the locally built desktop app with the same Java options as the packaged app.
$ErrorActionPreference = 'Stop'
try {
    $repo = Split-Path -Parent $PSScriptRoot
    $candidates = @($env:JAVA_HOME)
    $candidates += @(Get-ChildItem -LiteralPath (Join-Path $env:ProgramFiles 'Eclipse Adoptium') -Directory -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | ForEach-Object { $_.FullName })
    $candidates += @(Get-ChildItem -LiteralPath (Join-Path $env:ProgramFiles 'Java') -Directory -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | ForEach-Object { $_.FullName })
    $onPath = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($onPath) {
        $executable = Get-Item -LiteralPath $onPath.Source
        if ($executable.Target) { $executable = Get-Item -LiteralPath $executable.Target[0] }
        $candidates += Split-Path -Parent (Split-Path -Parent $executable.FullName)
    }
    $java = $null
    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        $release = Join-Path $candidate 'release'
        $binary = Join-Path $candidate 'bin\java.exe'
        if ((Test-Path -LiteralPath $release) -and (Test-Path -LiteralPath $binary)) {
            $version = Select-String -LiteralPath $release -Pattern '^JAVA_VERSION="(\d+)\.'
            if ($version -and [int]$version.Matches[0].Groups[1].Value -ge 17) { $java = $binary; break }
        }
    }
    if (-not $java) { throw 'Java 17 or later is required. Install a JDK or set JAVA_HOME to its installation folder.' }
    $jar = Get-ChildItem -LiteralPath (Join-Path $repo 'forge-gui-desktop\target') -Filter '*-jar-with-dependencies.jar' |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { throw 'Build forge-gui-desktop with Maven before launching Forge.' }
    $workingDirectory = Join-Path $repo 'forge-gui'
    if (-not (Test-Path -LiteralPath (Join-Path $workingDirectory 'res\skins\default\bg_splash.png'))) {
        throw 'Forge resources are missing. Restore forge-gui\res from the repository.'
    }
    [xml]$pom = Get-Content -Raw -LiteralPath (Join-Path $repo 'forge-gui-desktop\pom.xml')
    $options = ($pom.project.properties.'mandatory.java.args' + ' ' + $pom.project.properties.'addopen.java.args') -split '\s+'
    Push-Location -LiteralPath $workingDirectory
    try {
        & $java @options -jar $jar.FullName
        $result = $LASTEXITCODE
    } finally { Pop-Location }
    exit $result
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
