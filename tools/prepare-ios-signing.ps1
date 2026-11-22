$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.Windows.Forms

function Select-File {
    param(
        [Parameter(Mandatory = $true)][string]$Title,
        [Parameter(Mandatory = $true)][string]$Filter
    )
    $dialog = New-Object System.Windows.Forms.OpenFileDialog
    $dialog.Title = $Title
    $dialog.Filter = $Filter
    $dialog.Multiselect = $false
    if ($dialog.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) {
        throw "انتخاب فایل لغو شد."
    }
    return $dialog.FileName
}

function Convert-SecureStringToPlainText {
    param([Parameter(Mandatory = $true)][System.Security.SecureString]$Value)
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

try {
    Write-Host ""
    Write-Host "Raahat VPN - آماده‌سازی امن فایل Secret برای امضای iOS" -ForegroundColor Cyan
    Write-Host "این ابزار گواهی Apple یا پروفایل امضا تولید نمی‌کند؛ فقط فایل‌هایی را که از Apple Developer گرفته‌اید آماده می‌کند."
    Write-Host ""

    $teamId = (Read-Host "Apple Developer Team ID (ده کاراکتر)").Trim().ToUpperInvariant()
    if ($teamId -notmatch '^[A-Z0-9]{10}$') {
        throw "Team ID باید دقیقاً ۱۰ حرف یا عدد باشد."
    }

    $p12Path = Select-File -Title "گواهی Apple Development صادرشده با کلید خصوصی را انتخاب کنید" -Filter "PKCS#12 (*.p12)|*.p12"
    $appProfilePath = Select-File -Title "پروفایل توسعه برنامه com.raahat.vpn را انتخاب کنید" -Filter "Apple Provisioning Profile (*.mobileprovision)|*.mobileprovision"
    $extensionProfilePath = Select-File -Title "پروفایل توسعه افزونه com.raahat.vpn.packet-tunnel را انتخاب کنید" -Filter "Apple Provisioning Profile (*.mobileprovision)|*.mobileprovision"

    if (([IO.Path]::GetFullPath($appProfilePath)) -eq ([IO.Path]::GetFullPath($extensionProfilePath))) {
        throw "برای برنامه و افزونه باید دو provisioning profile جدا انتخاب کنید."
    }

    $securePassword = Read-Host "رمز فایل P12 را وارد کنید" -AsSecureString
    if ($securePassword.Length -eq 0) {
        throw "رمز فایل P12 نمی‌تواند خالی باشد."
    }
    $plainPassword = Convert-SecureStringToPlainText -Value $securePassword

    $documents = [Environment]::GetFolderPath("MyDocuments")
    $outputDirectory = Join-Path $documents "RaahatVPN-iOS-Signing"
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    $outputPath = Join-Path $outputDirectory "github-secrets.txt"

    $p12Base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($p12Path))
    $appProfileBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($appProfilePath))
    $extensionProfileBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($extensionProfilePath))

    $content = @"
# فایل حساس است. آن را در مخزن GitHub بارگذاری یا برای کسی ارسال نکنید.
# هر مقدار را جداگانه در Settings > Secrets and variables > Actions مخزن GitHub ثبت کنید.

RAAHAT_IOS_TEAM_ID=$teamId
RAAHAT_IOS_CERTIFICATE_P12_BASE64=$p12Base64
RAAHAT_IOS_CERTIFICATE_P12_PASSWORD=$plainPassword
RAAHAT_IOS_APP_PROFILE_BASE64=$appProfileBase64
RAAHAT_IOS_EXTENSION_PROFILE_BASE64=$extensionProfileBase64
"@

    [IO.File]::WriteAllText($outputPath, $content, (New-Object System.Text.UTF8Encoding($false)))

    $account = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    & icacls.exe $outputDirectory /inheritance:r /grant:r "${account}:(OI)(CI)F" "SYSTEM:(OI)(CI)F" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "تنظیم دسترسی محدود برای پوشه خروجی انجام نشد. فایل را به‌صورت دستی در محل امن نگهداری کنید."
    }

    Write-Host ""
    Write-Host "فایل آماده شد:" -ForegroundColor Green
    Write-Host $outputPath
    Write-Host ""
    Write-Host "۵ Secret لازم برای GitHub:" -ForegroundColor Yellow
    Write-Host "RAAHAT_IOS_TEAM_ID"
    Write-Host "RAAHAT_IOS_CERTIFICATE_P12_BASE64"
    Write-Host "RAAHAT_IOS_CERTIFICATE_P12_PASSWORD"
    Write-Host "RAAHAT_IOS_APP_PROFILE_BASE64"
    Write-Host "RAAHAT_IOS_EXTENSION_PROFILE_BASE64"
    Write-Host ""
    Write-Host "مقادیر را در GitHub ثبت کنید و این فایل را نگه دارید. آن را در چت یا مخزن عمومی نفرستید."
}
catch {
    Write-Host ""
    Write-Host ("خطا: " + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
finally {
    if ($plainPassword) {
        $plainPassword = $null
    }
}
