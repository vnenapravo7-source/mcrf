param(
    [string]$JavaHome = "$env:ProgramFiles/Java/jdk-17",
    [string]$AndroidSdk = "$env:LOCALAPPDATA/Android/Sdk",
    [string]$Task = 'assembleDebug',
    [string]$LiveFixtures = '',
    [switch]$TestRelease,
    [switch]$ReleaseSmoke
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if ((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'gradle/wrapper/gradle-wrapper.jar')).Hash.ToLowerInvariant() -ne '76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3') { throw 'Gradle wrapper SHA-256 mismatch' }
$androidCoreLock = Join-Path $PSScriptRoot 'android-core-lock.json'
$localCore = Test-Path -LiteralPath $androidCoreLock
$lock = if ($localCore) { Get-Content -LiteralPath $androidCoreLock -Raw | ConvertFrom-Json } else { Get-Content -LiteralPath (Join-Path $PSScriptRoot '../engine-lock.json') -Raw | ConvertFrom-Json }
$coreFile = if ($localCore) { $lock.file } else { $lock.android.file }
$coreHash = if ($localCore) { $lock.sha256 } else { $lock.android.sha256 }
$dependency = Join-Path $projectRoot ('work/platform-dependencies/'+$coreFile)
New-Item -ItemType Directory -Path (Split-Path $dependency), (Join-Path $PSScriptRoot 'app/libs') -Force | Out-Null
if (!(Test-Path -LiteralPath $dependency)) {
    if ($localCore) { throw "Pinned Android source-built core is missing: $dependency. Build it with ports/android/native-core/build.ps1, inspect its receipt and verify the lock hash before staging it." }
    Invoke-WebRequest ('https://github.com/'+$lock.repository+'/releases/download/'+$lock.tag+'/'+$lock.android.file) -OutFile $dependency
}
if ((Get-FileHash -LiteralPath $dependency -Algorithm SHA256).Hash.ToLowerInvariant() -ne $coreHash) { throw 'libbox SHA-256 mismatch' }
if ($localCore -and (Get-Item -LiteralPath $dependency).Length -ne $lock.bytes) { throw 'libbox size mismatch' }
Copy-Item -LiteralPath $dependency -Destination (Join-Path $PSScriptRoot 'app/libs/libbox.aar') -Force
if (!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) { throw 'JDK not found' }
if (!(Test-Path -LiteralPath $AndroidSdk)) { throw 'Android SDK not found' }
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidSdk
$env:GRADLE_USER_HOME = Join-Path $projectRoot 'work/toolchains/gradle-home'
# Windows Unix-domain sockets have a short path limit. Keep Gradle temporary sockets inside the project.
$taskJavaTemp = Join-Path $projectRoot 'work/toolchains/java-tmp'
New-Item -ItemType Directory -Path $taskJavaTemp -Force | Out-Null
$shortTemp = (New-Object -ComObject Scripting.FileSystemObject).GetFolder($taskJavaTemp).ShortPath
if ($shortTemp.Length -gt 85) { throw 'JDK socket path is too long: enable a short path for the project or use a shorter checkout path.' }
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
$env:JAVA_TOOL_OPTIONS = ($previousJavaOptions+' "-Djdk.net.unixdomain.tmpdir='+$shortTemp+'"').Trim()
Push-Location $PSScriptRoot
try {
    $extra = @()
    if ($LiveFixtures) { $extra += '-PliveFixtures='+[IO.Path]::GetFullPath($LiveFixtures) }
    if ($TestRelease) { $extra += '-PtestRelease=true' }
    if ($ReleaseSmoke) { $extra += '-PreleaseSmoke=true' }
    & (Join-Path $JavaHome 'bin/java.exe') -classpath 'gradle/wrapper/gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain $Task '--console=plain' @extra
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
} finally { Pop-Location; $env:JAVA_TOOL_OPTIONS = $previousJavaOptions }
