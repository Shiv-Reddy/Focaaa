$ErrorActionPreference = 'Stop'
$sealedPassword = Join-Path $PSScriptRoot '..\work\release-signing\password.dpapi'
if (-not (Test-Path -LiteralPath $sealedPassword)) { throw 'No locally sealed release password was found.' }
$securePassword = Get-Content -LiteralPath $sealedPassword -Raw | ConvertTo-SecureString
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    Write-Output ([Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer))
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
}
