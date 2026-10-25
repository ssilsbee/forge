param(
    [string]$SdkPath,
    [string]$JavaHome,
    [string]$MavenPath,
    [string]$MavenRepository
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$workspace = Split-Path $repo -Parent
if (!$SdkPath) { $SdkPath = Join-Path $workspace 'toolchain\android-sdk' }
if (!$JavaHome) { $JavaHome = Join-Path $workspace 'toolchain\jdk-17.0.20.1+1' }
if (!$MavenPath) { $MavenPath = Join-Path $workspace 'toolchain\apache-maven-3.8.1\bin\mvn.cmd' }
if (!$MavenRepository) { $MavenRepository = Join-Path $workspace 'maven-repository' }
foreach ($path in @($SdkPath, $JavaHome, $MavenPath, $MavenRepository)) {
    if (!(Test-Path -LiteralPath $path)) { throw "Required build path was not found: $path" }
}
# D8 exceeds Windows' command-line limit with the full workspace paths.
$mappings = @{ 'F:' = $repo; 'R:' = $MavenRepository; 'S:' = $SdkPath }
foreach ($drive in $mappings.Keys) {
    if (Test-Path "$drive\") { throw "Drive $drive is in use; free it before building." }
}
$mapped = @()
$oldJava = $env:JAVA_HOME; $oldPath = $env:PATH
$oldAndroid = $env:ANDROID_HOME; $oldSdk = $env:ANDROID_SDK_ROOT; $oldOpts = $env:MAVEN_OPTS
Push-Location $repo
try {
    foreach ($drive in $mappings.Keys) {
        subst $drive $mappings[$drive]
        if ($LASTEXITCODE -ne 0) { throw "Could not create temporary drive $drive" }
        $mapped += $drive
    }
    $env:JAVA_HOME = $JavaHome
    $env:PATH = (Join-Path $JavaHome 'bin') + ';' + $env:PATH
    $env:ANDROID_HOME = 'S:\'; $env:ANDROID_SDK_ROOT = 'S:\'
    $env:MAVEN_OPTS = "$oldOpts --add-exports=java.base/sun.security.pkcs=ALL-UNNAMED --add-exports=java.base/sun.security.x509=ALL-UNNAMED"
    & $MavenPath -B -f 'F:\pom.xml' '-Dmaven.repo.local=R:\' '-Dandroid.sdk.path=S:\' -Pandroid-debug -pl forge-gui-android -am -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }
    $apk = Get-ChildItem -LiteralPath (Join-Path $repo 'forge-gui-android\target') -Filter 'forge-android-*.apk' |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $signedDirectory = Join-Path $repo ('forge-gui-android\target\signed-llm\' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
    & (Join-Path $JavaHome 'bin\java.exe') -jar (Join-Path $repo 'forge-gui-android\tools\uber-apk-signer.jar') `
        --apks $apk.FullName --out $signedDirectory --allowResign `
        --zipAlignPath (Join-Path $SdkPath 'build-tools\35.0.0\zipalign.exe')
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
}
finally {
    foreach ($drive in $mapped) { subst $drive /D }
    $env:JAVA_HOME = $oldJava; $env:PATH = $oldPath
    $env:ANDROID_HOME = $oldAndroid; $env:ANDROID_SDK_ROOT = $oldSdk; $env:MAVEN_OPTS = $oldOpts
    Pop-Location
}
