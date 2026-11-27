$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$projectRoot = Split-Path -Parent $PSScriptRoot
$gradleVersion = '8.9'
$gradleDir = Join-Path $projectRoot ".gradle-dist\gradle-$gradleVersion"
if (Test-Path (Join-Path $gradleDir 'bin\gradle.bat')) { exit 0 }
$mirror = 'https://mirrors.huaweicloud.com/gradle'
$downloadDir = Join-Path $projectRoot ('.gradle-bootstrap\' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $downloadDir -Force | Out-Null
$archive = Join-Path $downloadDir 'gradle.zip'
function Download-GradleFile([string]$Uri, [string]$Destination) {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $Uri -OutFile $Destination -TimeoutSec 1800
            return
        } catch {
            if ($attempt -eq 3) { throw }
            Write-Host "Download failed; retry $attempt/3..."
        }
    }
}
Write-Host 'Downloading Gradle 8.9 from Huawei Cloud...'
$checksumFile = Join-Path $downloadDir 'gradle.sha256'
Download-GradleFile "$mirror/gradle-$gradleVersion-bin.zip.sha256" $checksumFile
$expected = (Get-Content -Raw -LiteralPath $checksumFile).Trim().ToLowerInvariant()
if ($expected -notmatch '^[0-9a-f]{64}$') { throw 'Invalid Gradle checksum response.' }
Download-GradleFile "$mirror/gradle-$gradleVersion-bin.zip" $archive
$actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant()
if ($actual -ne $expected) { throw 'Gradle ZIP checksum mismatch.' }
$extractDir = Join-Path $projectRoot ('.gradle-dist\extract-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $extractDir -Force | Out-Null
Expand-Archive -LiteralPath $archive -DestinationPath $extractDir
$extractedGradle = Join-Path $extractDir "gradle-$gradleVersion"
if (-not (Test-Path (Join-Path $extractedGradle 'bin\gradle.bat'))) { throw 'Gradle archive is incomplete.' }
Move-Item -LiteralPath $extractedGradle -Destination $gradleDir
Write-Host 'Gradle 8.9 downloaded from Huawei Cloud and SHA-256 verified.'
