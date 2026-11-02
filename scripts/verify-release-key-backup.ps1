param([Parameter(Mandatory = $true)][string]$BackupKeystorePath)

$ErrorActionPreference = 'Stop'
$original = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\work\release-signing\focaaa-release.p12')).Path
$backup = (Resolve-Path -LiteralPath $BackupKeystorePath).Path
if ($original -eq $backup) { throw 'Choose the copied keystore, not the original file.' }
$originalHash = (Get-FileHash -LiteralPath $original -Algorithm SHA256).Hash
$backupHash = (Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash
if ($originalHash -ne $backupHash) { throw 'The backup keystore does not match the release signing key.' }

$sealedPassword = Join-Path $PSScriptRoot '..\work\release-signing\password.dpapi'
$securePassword = Get-Content -LiteralPath $sealedPassword -Raw | ConvertTo-SecureString
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    $env:FOCAAA_RELEASE_STORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    & 'C:\Program Files\Android\Android Studio2\jbr\bin\keytool.exe' -list -keystore $backup -storetype PKCS12 -alias focaaa -storepass:env FOCAAA_RELEASE_STORE_PASSWORD *> $null
    if ($LASTEXITCODE -ne 0) { throw 'The backup file did not open with the saved release password.' }
    Write-Output 'Verified: copied keystore matches the original and opens with the saved password.'
} finally {
    Remove-Item Env:FOCAAA_RELEASE_STORE_PASSWORD -ErrorAction SilentlyContinue
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
}
