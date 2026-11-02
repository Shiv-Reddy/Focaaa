param(
    [string]$SigningFolder = (Join-Path $PSScriptRoot '..\work\release-signing')
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$keyStore = Join-Path $SigningFolder 'focaaa-release.p12'
$sealedPassword = Join-Path $SigningFolder 'password.dpapi'
if (-not (Test-Path -LiteralPath $keyStore) -or -not (Test-Path -LiteralPath $sealedPassword)) {
    throw 'The release keystore and its locally sealed password are both required.'
}

$securePassword = Get-Content -LiteralPath $sealedPassword -Raw | ConvertTo-SecureString
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    $env:FOCAAA_RELEASE_STORE_FILE = (Resolve-Path -LiteralPath $keyStore).Path
    $env:FOCAAA_RELEASE_STORE_PASSWORD = $password
    $env:FOCAAA_RELEASE_KEY_ALIAS = 'focaaa'
    $env:FOCAAA_RELEASE_KEY_PASSWORD = $password
    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio2\jbr'
    Push-Location $projectRoot
    try {
        & .\gradlew.bat :app:assembleRelease --no-daemon --no-configuration-cache --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'The signed release build failed.' }
    } finally {
        Pop-Location
    }
} finally {
    Remove-Item Env:FOCAAA_RELEASE_STORE_FILE,Env:FOCAAA_RELEASE_STORE_PASSWORD,Env:FOCAAA_RELEASE_KEY_ALIAS,Env:FOCAAA_RELEASE_KEY_PASSWORD -ErrorAction SilentlyContinue
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    $password = $null
}
