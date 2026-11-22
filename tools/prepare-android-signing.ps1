# Creates a private Android signing key for Raahat VPN.
# Run on Windows with Java 17+ (Android Studio includes a compatible JDK).
$ErrorActionPreference = "Stop"

$keytoolCandidates = @()
if ($env:JAVA_HOME) {
    $keytoolCandidates += (Join-Path $env:JAVA_HOME "bin\keytool.exe")
}
if ($env:ProgramFiles) {
    $keytoolCandidates += (Join-Path $env:ProgramFiles "Android\Android Studio\jbr\bin\keytool.exe")
    $keytoolCandidates += (Join-Path $env:ProgramFiles "Eclipse Adoptium\jdk-17\bin\keytool.exe")
}
if ($env:LOCALAPPDATA) {
    $keytoolCandidates += (Join-Path $env:LOCALAPPDATA "Programs\Android Studio\jbr\bin\keytool.exe")
}

$keytoolCommand = Get-Command "keytool.exe" -ErrorAction SilentlyContinue
if ($keytoolCommand) {
    $keytoolCandidates += $keytoolCommand.Source
}
$keytoolPath = $keytoolCandidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1

if (-not $keytoolPath) {
    Write-Host "Java keytool was not found." -ForegroundColor Red
    Write-Host "Install Android Studio, then run this file again. Android Studio includes the required Java tools."
    Read-Host "Press Enter to close"
    exit 1
}

$documents = [Environment]::GetFolderPath("MyDocuments")
$signingDirectory = Join-Path $documents "RaahatVPN-Signing"
New-Item -ItemType Directory -Path $signingDirectory -Force | Out-Null

$keystorePath = Join-Path $signingDirectory "raahat-release.p12"
$secretsPath = Join-Path $signingDirectory "GitHub-Actions-Secrets.txt"
if ((Test-Path -LiteralPath $keystorePath) -or (Test-Path -LiteralPath $secretsPath)) {
    Write-Host "A signing key or secret file already exists. Nothing was overwritten." -ForegroundColor Yellow
    Write-Host "Folder: $signingDirectory"
    Read-Host "Press Enter to close"
    exit 1
}

$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $passwordBytes = New-Object byte[] 48
    $rng.GetBytes($passwordBytes)
    $password = [Convert]::ToBase64String($passwordBytes).TrimEnd("=").Replace("+", "A").Replace("/", "B")
}
finally {
    $rng.Dispose()
}

$env:RAAHAT_ANDROID_STORE_PASSWORD = $password
$env:RAAHAT_ANDROID_KEY_PASSWORD = $password
$keyAlias = "raahat-release"
$env:RAAHAT_ANDROID_KEY_ALIAS = $keyAlias

try {
    $keytoolArguments = @(
        "-genkeypair",
        "-v",
        "-keystore", $keystorePath,
        "-storetype", "PKCS12",
        "-alias", $keyAlias,
        "-keyalg", "RSA",
        "-keysize", "4096",
        "-sigalg", "SHA256withRSA",
        "-validity", "10000",
        "-dname", "CN=Raahat VPN, OU=Android Release, O=Raahat VPN, L=Unknown, ST=Unknown, C=US",
        "-storepass:env", "RAAHAT_ANDROID_STORE_PASSWORD",
        "-keypass:env", "RAAHAT_ANDROID_KEY_PASSWORD"
    )
    & $keytoolPath @keytoolArguments
    if ($LASTEXITCODE -ne 0) {
        throw "keytool failed with exit code $LASTEXITCODE."
    }

    $keystoreBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystorePath))
    $secretText = @"
RAAHAT_ANDROID_KEYSTORE_BASE64
$keystoreBase64

RAAHAT_ANDROID_STORE_PASSWORD
$password

RAAHAT_ANDROID_KEY_ALIAS
$keyAlias

RAAHAT_ANDROID_KEY_PASSWORD
$password
"@
    Set-Content -LiteralPath $secretsPath -Value $secretText -Encoding ASCII

    try {
        $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
        & icacls.exe $secretsPath /inheritance:r /grant:r "$($identity):(F)" | Out-Null
    }
    catch {
        Write-Warning "Could not restrict the secret file ACL automatically. Keep the file private."
    }

    Write-Host ""
    Write-Host "Signing key created successfully." -ForegroundColor Green
    Write-Host "Private keystore: $keystorePath"
    Write-Host "GitHub secret values: $secretsPath"
    Write-Host ""
    Write-Host "IMPORTANT: Keep both files private. Back up the .p12 file in a secure location."
    Write-Host "Never upload either file to the public repository or send them in chat."
}
finally {
    Remove-Item Env:RAAHAT_ANDROID_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:RAAHAT_ANDROID_KEY_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:RAAHAT_ANDROID_KEY_ALIAS -ErrorAction SilentlyContinue
}

Read-Host "Press Enter to close"
