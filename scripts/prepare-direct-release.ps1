param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][string]$ExpectedFirebaseAppId,
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

$apk = (Resolve-Path -LiteralPath $ApkPath).Path
Assert-That ((Get-Item -LiteralPath $apk).Extension -eq '.apk') 'The input must be an APK file.'
$sdk = if ($SdkRoot) { $SdkRoot } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$tools = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
Assert-That ($null -ne $tools) 'Android SDK build-tools are missing.'
$signer = Join-Path $tools.FullName 'apksigner.bat'
$aapt = Join-Path $tools.FullName 'aapt.exe'
Assert-That ((Test-Path -LiteralPath $signer) -and (Test-Path -LiteralPath $aapt)) 'apksigner or aapt is missing from Android SDK build-tools.'
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
if (-not $env:JAVA_HOME) {
    $studioJbr = 'C:\Program Files\Android\Android Studio2\jbr'
    if (Test-Path -LiteralPath (Join-Path $studioJbr 'bin\java.exe')) { $env:JAVA_HOME = $studioJbr }
}
Assert-That (($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe')))) 'JAVA_HOME must point to an installed JDK to verify the APK.'

$previousErrorPreference = $ErrorActionPreference
try {
    # Java may write a harmless restricted-method warning to stderr on newer JDKs.
    # Judge the signer by its exit code and required signature output below.
    $ErrorActionPreference = 'Continue'
    $signature = @(& $signer verify --verbose --print-certs $apk 2>&1)
} finally {
    $ErrorActionPreference = $previousErrorPreference
}
Assert-That ($LASTEXITCODE -eq 0) 'The APK signature did not verify.'
Assert-That (($signature -join "`n") -match 'Verified using v2 scheme \(APK Signature Scheme v2\): true') 'APK Signature Scheme v2 is required.'
Assert-That (-not (($signature -join "`n") -match 'CN=Android Debug')) 'A debug signing certificate cannot be published.'
$certificate = ($signature | Select-String 'certificate SHA-256 digest: ([a-fA-F0-9]{64})' | Select-Object -First 1)
Assert-That ($null -ne $certificate) 'Could not read the APK signing certificate fingerprint.'
$certificateSha = $certificate.Matches[0].Groups[1].Value.ToLowerInvariant()

$badging = @(& $aapt dump badging $apk 2>&1)
Assert-That ($LASTEXITCODE -eq 0) 'Could not inspect the APK manifest.'
$packageLine = ($badging | Where-Object { $_ -match '^package:' } | Select-Object -First 1)
Assert-That ($packageLine -match "name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'") 'Package, version code, or version name is missing.'
$packageName, $versionCode, $versionName = $Matches[1], [int]$Matches[2], $Matches[3]
Assert-That ($packageName -eq 'com.focaaa.app') "Unexpected package: $packageName"
Assert-That ($versionCode -gt 0 -and $versionName -match '^[0-9A-Za-z._-]+$') 'The release version is invalid.'
Assert-That (-not ($badging -match '^application-debuggable')) 'A debuggable APK cannot be published.'
Assert-That (($badging -match "^sdkVersion:'26'$").Count -gt 0) 'The documented Android 8.0 minimum does not match this APK.'

$resourceLines = @(& $aapt dump --values resources $apk 2>&1)
Assert-That ($LASTEXITCODE -eq 0) 'Could not inspect APK resources.'
$firebaseAppId = Resource-Value $resourceLines 'google_app_id'
$relayUrl = Resource-Value $resourceLines 'relay_base_url'
$appCheckSiteKey = Resource-Value $resourceLines 'app_check_site_key'
Assert-That ($firebaseAppId -eq $ExpectedFirebaseAppId) 'The APK uses a different Firebase Android app than the release registration.'
Assert-That ($relayUrl -eq 'https://focaaa-relay.focaaa-free-relay.workers.dev') 'The APK uses the wrong relay URL.'
Assert-That ($appCheckSiteKey -match '^[A-Za-z0-9_-]{20,}$') 'The APK is missing its production Android App Check site key.'

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($apk)
try {
    $nativeLibraries = @($archive.Entries | Where-Object { $_.FullName -match '^lib/[^/]+/libfocaaa_openmls\.so$' } | ForEach-Object FullName | Sort-Object)
    $expectedLibraries = @('lib/arm64-v8a/libfocaaa_openmls.so', 'lib/armeabi-v7a/libfocaaa_openmls.so', 'lib/x86_64/libfocaaa_openmls.so' | Sort-Object)
    Assert-That (($nativeLibraries -join '|') -eq ($expectedLibraries -join '|')) 'The OpenMLS native ABI set is incomplete or unexpected.'
} finally { $archive.Dispose() }

$size = (Get-Item -LiteralPath $apk).Length
Assert-That ($size -le 25MB) 'The APK exceeds Cloudflare Free static assets 25 MiB file limit.'
Assert-That ($DeviceTested.IsPresent -xor $EarlyAccess.IsPresent) 'Specify either -DeviceTested after the two-phone check or -EarlyAccess for a user-approved pretest release.'

$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$apkName = "focaaa-$versionName.apk"
$stage = Join-Path $OutputRoot "v$versionCode-$hash"
Assert-That (-not (Test-Path -LiteralPath $stage)) 'This exact release is already staged; inspect it instead of overwriting it.'
New-Item -ItemType Directory -Path $stage -Force | Out-Null
Copy-Item -LiteralPath $apk -Destination (Join-Path $stage $apkName)
Assert-That (((Get-FileHash -LiteralPath (Join-Path $stage $apkName) -Algorithm SHA256).Hash.ToLowerInvariant()) -eq $hash) 'The copied APK differs from the tested input.'
$utf8 = [System.Text.UTF8Encoding]::new($false)
$source = Join-Path $PSScriptRoot '..\release-site'
$page = [System.IO.File]::ReadAllText((Join-Path $source 'index.html'))
$releaseStatus = if ($EarlyAccess) { 'Early access build' } else { 'Verified release' }
$page = $page.Replace('{{VERSION}}', $versionName).Replace('{{APK_FILENAME}}', $apkName).
    Replace('{{APK_SIZE}}', ($size / 1MB).ToString('0.0', [cultureinfo]::InvariantCulture)).
    Replace('{{SHA256}}', $hash).Replace('{{RELEASE_STATUS}}', $releaseStatus)
Assert-That (-not ($page -match '\{\{[A-Z_]+\}\}')) 'The download page has an unfilled release placeholder.'
[System.IO.File]::WriteAllText((Join-Path $stage 'index.html'), $page, $utf8)
Copy-Item -LiteralPath (Join-Path $source 'privacy.html') -Destination $stage
Copy-Item -LiteralPath (Join-Path $source 'style.css') -Destination $stage
Copy-Item -LiteralPath (Join-Path $source 'favicon.svg') -Destination $stage
Copy-Item -LiteralPath (Join-Path $source '_headers') -Destination $stage
Copy-Item -LiteralPath (Join-Path $source 'wrangler.jsonc') -Destination $stage
Copy-Item -LiteralPath (Join-Path $source '.assetsignore') -Destination $stage
[System.IO.File]::WriteAllText((Join-Path $stage 'SHA256SUMS.txt'), "$hash  $apkName`n", $utf8)
$manifest = [ordered]@{ packageName = $packageName; versionCode = $versionCode; versionName = $versionName; apk = $apkName; sha256 = $hash; certificateSha256 = $certificateSha; firebaseAppId = $firebaseAppId; appCheckProvider = 'recaptchaEnterprise'; relayUrl = $relayUrl; earlyAccess = $EarlyAccess.IsPresent; deviceTested = $DeviceTested.IsPresent }
[System.IO.File]::WriteAllText((Join-Path $stage 'release.json'), ($manifest | ConvertTo-Json) + "`n", $utf8)
Write-Output "Staged $releaseStatus`: $stage"
Write-Output "APK SHA-256: $hash"
Write-Output "Signing certificate SHA-256: $certificateSha"
