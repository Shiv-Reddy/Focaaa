param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][string]$ExpectedFirebaseAppId,
    [Parameter(Mandatory = $true)][string]$AppCheckKeyMetadataPath,
    [switch]$DeviceTested,
    [switch]$EarlyAccess,
    [string]$SdkRoot,
    [string]$JavaHome,
    [string]$OutputRoot
)

$ErrorActionPreference = 'Stop'
if (-not $OutputRoot) { $OutputRoot = Join-Path $PSScriptRoot '..\work\release-staging' }

function Assert-That([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Resource-Value([string[]]$Lines, [string]$Name) {
    for ($index = 0; $index -lt $Lines.Count - 1; $index++) {
        if ($Lines[$index] -match ":string/$([regex]::Escape($Name)):") {
            if ($Lines[$index + 1] -match '^\s+\(string(?:8|16)?\) "([^"]*)"') {
                return $Matches[1]
            }
        }
    }
    return $null
}

Assert-That ($DeviceTested.IsPresent -xor $EarlyAccess.IsPresent) 'Specify -DeviceTested after two-phone verification or -EarlyAccess.'
$apk = (Resolve-Path -LiteralPath $ApkPath).Path
Assert-That ((Get-Item -LiteralPath $apk).Extension -eq '.apk') 'The input must be an APK.'
$sdk = if ($SdkRoot) { $SdkRoot } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
Assert-That ($null -ne $buildTools) 'Android SDK build-tools are missing.'
$signer = Join-Path $buildTools.FullName 'apksigner.bat'
$aapt = Join-Path $buildTools.FullName 'aapt.exe'
Assert-That ((Test-Path -LiteralPath $signer) -and (Test-Path -LiteralPath $aapt)) 'apksigner or aapt is missing.'
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
if (-not $env:JAVA_HOME) {
    $studioJbr = 'C:\Program Files\Android\Android Studio2\jbr'
    if (Test-Path -LiteralPath (Join-Path $studioJbr 'bin\java.exe')) { $env:JAVA_HOME = $studioJbr }
}
Assert-That ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) 'JAVA_HOME must point to a JDK.'

# The signer can emit a harmless JVM warning to stderr; the exit code and signature lines decide success.
$previousErrorPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $signature = @(& $signer verify --verbose --print-certs $apk 2>&1)
} finally { $ErrorActionPreference = $previousErrorPreference }
Assert-That ($LASTEXITCODE -eq 0) 'The APK signature did not verify.'
Assert-That (($signature -join "`n") -match 'Verified using v2 scheme \(APK Signature Scheme v2\): true') 'APK Signature Scheme v2 is required.'
Assert-That (-not (($signature -join "`n") -match 'CN=Android Debug')) 'A debug APK cannot be published.'
$certificate = $signature | Select-String 'certificate SHA-256 digest: ([a-fA-F0-9]{64})' | Select-Object -First 1
Assert-That ($null -ne $certificate) 'Could not read the signing certificate.'
$certificateSha = $certificate.Matches[0].Groups[1].Value.ToLowerInvariant()
$expectedCertificateSha = '57648f061c17994ac2ea316cadb6bd1b44507163fd11e1b64257f4cfcd67efb2'
Assert-That ($certificateSha -eq $expectedCertificateSha) 'The APK is not signed with the permanent Focaaa release key.'

$badging = @(& $aapt dump badging $apk 2>&1)
Assert-That ($LASTEXITCODE -eq 0) 'Could not inspect the APK manifest.'
$packageLine = $badging | Where-Object { $_ -match '^package:' } | Select-Object -First 1
Assert-That ($packageLine -match "name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'") 'Package or version metadata is missing.'
$packageName, $versionCode, $versionName = $Matches[1], [int]$Matches[2], $Matches[3]
Assert-That ($packageName -eq 'com.focaaa.app') "Unexpected Android package: $packageName"
Assert-That ($versionCode -gt 0 -and $versionName -match '^[0-9A-Za-z._-]+$') 'Invalid version metadata.'
Assert-That (-not ($badging -match '^application-debuggable')) 'A debuggable APK cannot be published.'
Assert-That (($badging -match "^sdkVersion:'26'$").Count -gt 0) 'The Android 8 minimum does not match this APK.'

$resourceLines = @(& $aapt dump --values resources $apk 2>&1)
Assert-That ($LASTEXITCODE -eq 0) 'Could not inspect APK resources.'
$firebaseAppId = Resource-Value $resourceLines 'google_app_id'
$relayUrl = Resource-Value $resourceLines 'relay_base_url'
$siteKey = Resource-Value $resourceLines 'app_check_site_key'
Assert-That ($firebaseAppId -eq $ExpectedFirebaseAppId) 'The APK uses the wrong Firebase app registration.'
Assert-That ($relayUrl -eq 'https://focaaa-relay.focaaa-free-relay.workers.dev') 'The APK uses the wrong relay.'
Assert-That ($siteKey -match '^[A-Za-z0-9_-]{20,}$') 'The release App Check key is missing.'
$keyMetadata = Get-Content -LiteralPath (Resolve-Path -LiteralPath $AppCheckKeyMetadataPath).Path -Raw | ConvertFrom-Json
Assert-That ($keyMetadata.name -match '^projects/[0-9]+/keys/([A-Za-z0-9_-]+)$') 'Invalid App Check key metadata.'
Assert-That ($Matches[1] -eq $siteKey) 'The APK does not contain the verified direct-distribution App Check key.'
Assert-That ($keyMetadata.androidSettings.supportNonGoogleAppStoreDistribution -eq $true) 'The App Check key does not support direct APK distribution.'
Assert-That ($keyMetadata.androidSettings.allowAllPackageNames -ne $true -and
    @($keyMetadata.androidSettings.allowedPackageNames).Count -eq 1 -and
    $keyMetadata.androidSettings.allowedPackageNames[0] -eq 'com.focaaa.app') 'The App Check key must be restricted to com.focaaa.app.'

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($apk)
try {
    $nativeLibraries = @($archive.Entries | Where-Object { $_.FullName -match '^lib/[^/]+/libfocaaa_openmls\.so$' } | ForEach-Object FullName | Sort-Object)
    $expectedLibraries = @('lib/arm64-v8a/libfocaaa_openmls.so', 'lib/armeabi-v7a/libfocaaa_openmls.so', 'lib/x86_64/libfocaaa_openmls.so' | Sort-Object)
    Assert-That (($nativeLibraries -join '|') -eq ($expectedLibraries -join '|')) 'The native OpenMLS ABI set is incomplete.'
} finally { $archive.Dispose() }

$size = (Get-Item -LiteralPath $apk).Length
Assert-That ($size -le 25MB) 'The APK exceeds the Cloudflare Free static asset size limit.'
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$apkName = "focaaa-$versionName.apk"
$stage = Join-Path $OutputRoot "v$versionCode-$hash"
Assert-That (-not (Test-Path -LiteralPath $stage)) 'This exact release is already staged; inspect it instead of overwriting it.'
New-Item -ItemType Directory -Path $stage -Force | Out-Null
Copy-Item -LiteralPath $apk -Destination (Join-Path $stage $apkName)
Assert-That (((Get-FileHash -LiteralPath (Join-Path $stage $apkName) -Algorithm SHA256).Hash.ToLowerInvariant()) -eq $hash) 'The staged APK differs from the signed build.'

$utf8 = [System.Text.UTF8Encoding]::new($false)
$source = Join-Path $PSScriptRoot '..\release-site'
$page = [System.IO.File]::ReadAllText((Join-Path $source 'index.html'))
$releaseStatus = if ($EarlyAccess) { 'Early access build' } else { 'Verified release' }
$page = $page.Replace('{{VERSION}}', $versionName).Replace('{{APK_FILENAME}}', $apkName).
    Replace('{{APK_SIZE}}', ($size / 1MB).ToString('0.0', [cultureinfo]::InvariantCulture)).
    Replace('{{SHA256}}', $hash).Replace('{{RELEASE_STATUS}}', $releaseStatus)
Assert-That (-not ($page -match '\{\{[A-Z_]+\}\}')) 'The download page has an unfilled placeholder.'
[System.IO.File]::WriteAllText((Join-Path $stage 'index.html'), $page, $utf8)
@('privacy.html', 'terms.html', 'style.css', 'favicon.svg', '_headers', 'wrangler.jsonc', '.assetsignore') |
    ForEach-Object { Copy-Item -LiteralPath (Join-Path $source $_) -Destination $stage }
[System.IO.File]::WriteAllText((Join-Path $stage 'SHA256SUMS.txt'), "$hash  $apkName`n", $utf8)
$manifest = [ordered]@{
    packageName = $packageName; versionCode = $versionCode; versionName = $versionName
    apk = $apkName; sha256 = $hash; certificateSha256 = $certificateSha
    firebaseAppId = $firebaseAppId; appCheckProvider = 'recaptchaEnterprise'
    appCheckDirectDistribution = $true; relayUrl = $relayUrl
    earlyAccess = $EarlyAccess.IsPresent; deviceTested = $DeviceTested.IsPresent
}
[System.IO.File]::WriteAllText((Join-Path $stage 'release.json'), ($manifest | ConvertTo-Json) + "`n", $utf8)
Write-Output "Staged $releaseStatus`: $stage"
Write-Output "APK SHA-256: $hash"
Write-Output "Signing certificate SHA-256: $certificateSha"
