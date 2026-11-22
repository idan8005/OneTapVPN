using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;

namespace RaahatVPN;

/// <summary>
/// Arms a machine-wide outbound firewall lock while the Raahat TUN is active.
/// Existing local outbound allow rules are snapshotted and disabled so they
/// cannot bypass the tunnel. A SYSTEM scheduled task restores the snapshot
/// after an unexpected application exit or system restart.
/// </summary>
public sealed class WindowsKillSwitch : IDisposable
{
    public const string TunnelInterfaceAlias = "RaahatVPN";
    public const string FirewallGroup = "RaahatVPN Kill Switch";
    public const string RecoveryTaskName = "RaahatVPN_KillSwitch_Recovery";

    private const string RecoveryFileName = "recovery.ps1";
    private const string StateFileName = "state.json";
    private const string StateLockFileName = "recovery.lock";

    private readonly string _rootDirectory;
    private readonly string _statePath;
    private readonly string _recoveryScriptPath;
    private bool _active;
    private bool _disposed;

    public WindowsKillSwitch()
    {
        _rootDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
            "RaahatVPN",
            "KillSwitch");
        _statePath = Path.Combine(_rootDirectory, StateFileName);
        _recoveryScriptPath = Path.Combine(_rootDirectory, RecoveryFileName);
    }

    public bool IsActive => File.Exists(_statePath);

    public static string BuildRecoveryScript() => RecoveryScript;

    /// <summary>
    /// Recovers an interrupted previous session before allowing a new connection.
    /// If another Raahat process still owns an active kill switch, startup is refused.
    /// </summary>
    public static async Task RecoverExistingStateAsync()
    {
        var root = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
            "RaahatVPN",
            "KillSwitch");
        var statePath = Path.Combine(root, StateFileName);
        if (!File.Exists(statePath))
            return;

        Directory.CreateDirectory(root);
        await SecureDirectoryAsync(root);
        var scriptPath = Path.Combine(root, RecoveryFileName);
        await File.WriteAllTextAsync(scriptPath, RecoveryScript, new UTF8Encoding(false));

        var result = await RunPowerShellFileAsync(scriptPath, TimeSpan.FromSeconds(20));
        if (result.Contains("ACTIVE", StringComparison.Ordinal))
            throw new InvalidOperationException(
                "یک نمونهٔ دیگر از راحت وی‌پی‌ان هنوز Kill Switch را در اختیار دارد. ابتدا همان پنجره را قطع اتصال کنید.");

        // After a crash or reboot the scheduled SYSTEM task deliberately keeps
        // outbound traffic blocked. When the user launches Raahat again, this
        // startup path explicitly requests rollback to the saved firewall state.
        if (result.Contains("LOCKED_RECOVERY_REQUIRED", StringComparison.Ordinal) &&
            File.Exists(statePath))
        {
            await UpdateStateFileAsync(
                root,
                statePath,
                state => state with { RestoreRequested = true });

            result = await RunPowerShellFileAsync(scriptPath, TimeSpan.FromSeconds(25));
        }

        if (File.Exists(statePath))
            throw new InvalidOperationException(
                "بازیابی تنظیمات فایروال هنوز کامل نشده است. اتصال جدید عمداً متوقف شد تا اینترنت بدون محافظت فعال نشود.");
    }

    /// <summary>
    /// Prepares firewall exceptions and enables the outbound block. The server
    /// endpoint must already be resolved to a public IPv4 address so no DNS
    /// request is needed while the kill switch is armed.
    /// </summary>
    public async Task EnsureActiveAsync(string enginePath, IPAddress endpointAddress, int endpointPort)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (!OperatingSystem.IsWindows())
            throw new PlatformNotSupportedException("Kill Switch فقط در ویندوز پشتیبانی می‌شود.");
        if (endpointAddress.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork ||
            !IsPublicIpv4(endpointAddress) ||
            endpointPort is < 1 or > 65535)
            throw new InvalidOperationException("نشانی سرور برای قانون امن فایروال معتبر نیست.");
        if (!File.Exists(enginePath))
            throw new FileNotFoundException("موتور sing-box پیدا نشد.", enginePath);

        if (_active)
        {
            if (!File.Exists(_statePath))
                throw new InvalidOperationException(
                    "فایل وضعیت Kill Switch پیدا نشد؛ برای جلوگیری از اتصال بدون محافظت، تلاش بعدی لغو شد.");
            await AddEngineEndpointRuleAsync(enginePath, endpointAddress, endpointPort);
            return;
        }

        Directory.CreateDirectory(_rootDirectory);
        await SecureDirectoryAsync(_rootDirectory);
        await File.WriteAllTextAsync(_recoveryScriptPath, RecoveryScript, new UTF8Encoding(false));

        if (File.Exists(_statePath))
            throw new InvalidOperationException(
                "یک وضعیت بازیابی فایروال از اجرای قبلی باقی مانده است. برنامه را دوباره باز کنید تا بازیابی انجام شود.");

        var snapshot = await CaptureFirewallSnapshotAsync();
        var ownerExecutable = Environment.ProcessPath ??
            Process.GetCurrentProcess().MainModule?.FileName ??
            throw new InvalidOperationException("مسیر فایل اجرایی راحت وی‌پی‌ان مشخص نشد.");

        var state = new RecoveryState(
            Environment.ProcessId,
            Path.GetFullPath(ownerExecutable),
            RestoreRequested: false,
            snapshot.Profiles,
            snapshot.AllowRules);

        await WriteStateAsync(state);
        try
        {
            await RegisterRecoveryTaskAsync();
            await ApplyKillSwitchRulesAsync(enginePath, endpointAddress, endpointPort);
            _active = true;
        }
        catch
        {
            // The recovery task was registered before changing the firewall.
            // Ask it to restore any partial changes; it will retry every minute
            // if Windows temporarily refuses the rollback.
            try { await RequestRecoveryAsync(); } catch { }
            throw new InvalidOperationException(
                "ویندوز اجازهٔ فعال‌سازی امن Kill Switch را نداد. تغییرات ناقص برای بازیابی خودکار علامت‌گذاری شدند؛ اتصال VPN شروع نشد.");
        }
    }

    public async Task DeactivateAsync()
    {
        if (_disposed && !_active && !File.Exists(_statePath))
            return;
        if (!File.Exists(_statePath))
        {
            _active = false;
            return;
        }

        await UpdateStateAsync(state => state with { RestoreRequested = true });
        var result = await RunPowerShellFileAsync(_recoveryScriptPath, TimeSpan.FromSeconds(25));
        if (File.Exists(_statePath))
        {
            // The scheduled recovery task is still registered and will retry.
            // Keep IsActive true so the UI does not claim that protection ended.
            throw new InvalidOperationException(
                "تونل متوقف شد، اما بازیابی تنظیمات فایروال هنوز کامل نشده است. اینترنت تا زمان بازیابی مسدود می‌ماند؛ دکمهٔ قطع اتصال را دوباره بزنید.");
        }

        _active = false;
    }

    public async Task RequestRecoveryAsync()
    {
        if (!File.Exists(_statePath))
            return;
        await UpdateStateAsync(state => state with { RestoreRequested = true });
        await RunPowerShellFileAsync(_recoveryScriptPath, TimeSpan.FromSeconds(25));
        if (File.Exists(_statePath))
            throw new InvalidOperationException("بازیابی فایروال هنوز در صف تکرار خودکار است.");
        _active = false;
    }

    public static string BuildFirewallLockScript(
        string enginePath,
        IPAddress endpointAddress,
        int endpointPort)
    {
        if (string.IsNullOrWhiteSpace(enginePath))
            throw new ArgumentException("Engine path is required.", nameof(enginePath));
        if (!IsPublicIpv4(endpointAddress) || endpointPort is < 1 or > 65535)
            throw new ArgumentException("A public IPv4 endpoint and valid port are required.");

        var engine = EscapePowerShellLiteral(Path.GetFullPath(enginePath));
        var endpoint = EscapePowerShellLiteral(endpointAddress.ToString());

        return $$"""
$ErrorActionPreference = 'Stop'
$group = '{{FirewallGroup}}'
$enginePath = '{{engine}}'
$endpoint = '{{endpoint}}'
$port = {{endpointPort}}
$lanRanges = @('10.0.0.0/8','100.64.0.0/10','127.0.0.0/8','169.254.0.0/16','172.16.0.0/12','192.168.0.0/16','::1/128','fc00::/7','fe80::/10')

Remove-NetFirewallRule -PolicyStore PersistentStore -Group $group -ErrorAction SilentlyContinue | Out-Null
New-NetFirewallRule -PolicyStore PersistentStore -Name 'RaahatVPN_KillSwitch_Tunnel' -DisplayName 'Raahat VPN - Allow tunnel traffic' -Group $group -Direction Outbound -Action Allow -Profile Any -Enabled True -InterfaceAlias '{{TunnelInterfaceAlias}}' -Protocol Any | Out-Null
New-NetFirewallRule -PolicyStore PersistentStore -Name 'RaahatVPN_KillSwitch_LAN' -DisplayName 'Raahat VPN - Allow private LAN' -Group $group -Direction Outbound -Action Allow -Profile Any -Enabled True -RemoteAddress $lanRanges -Protocol Any | Out-Null
New-NetFirewallRule -PolicyStore PersistentStore -Name 'RaahatVPN_KillSwitch_Engine_{{EndpointRuleSuffix(endpointAddress, endpointPort)}}' -DisplayName 'Raahat VPN - Allow sing-box server endpoint' -Group $group -Direction Outbound -Action Allow -Profile Any -Enabled True -Program $enginePath -Protocol TCP -RemoteAddress $endpoint -RemotePort $port | Out-Null

# Disable pre-existing local outbound allow rules so they cannot bypass the
# tunnel after the default outbound action is changed to Block. Their exact
# enabled states were saved before any firewall change and are restored later.
$existingAllows = @(Get-NetFirewallRule -PolicyStore PersistentStore -Enabled True -Direction Outbound -Action Allow -ErrorAction Stop | Where-Object { $_.Group -ne $group })
foreach ($rule in $existingAllows) {
    Disable-NetFirewallRule -PolicyStore PersistentStore -Name $rule.Name -ErrorAction Stop | Out-Null
}

Set-NetFirewallProfile -PolicyStore PersistentStore -Name @('Domain','Private','Public') -Enabled True -DefaultOutboundAction Block -ErrorAction Stop
$effective = @(Get-NetFirewallProfile -PolicyStore ActiveStore)
if (@($effective | Where-Object { [string]$_.DefaultOutboundAction -ne 'Block' -or $_.Enabled -ne $true }).Count -gt 0) {
    throw 'Effective firewall policy did not enter outbound Block mode.'
}
if (-not (Get-NetFirewallRule -PolicyStore ActiveStore -Name 'RaahatVPN_KillSwitch_Tunnel' -ErrorAction SilentlyContinue)) {
    throw 'Tunnel allow rule is not present in effective firewall policy.'
}
Write-Output 'ARMED'
""";
    }

    public static string BuildEndpointAllowScript(
        string enginePath,
        IPAddress endpointAddress,
        int endpointPort)
    {
        if (string.IsNullOrWhiteSpace(enginePath))
            throw new ArgumentException("Engine path is required.", nameof(enginePath));
        if (!IsPublicIpv4(endpointAddress) || endpointPort is < 1 or > 65535)
            throw new ArgumentException("A public IPv4 endpoint and valid port are required.");

        var engine = EscapePowerShellLiteral(Path.GetFullPath(enginePath));
        var endpoint = EscapePowerShellLiteral(endpointAddress.ToString());
        var ruleName = "RaahatVPN_KillSwitch_Engine_" + EndpointRuleSuffix(endpointAddress, endpointPort);

        return $$"""
$ErrorActionPreference = 'Stop'
$ruleName = '{{ruleName}}'
if (-not (Get-NetFirewallRule -PolicyStore PersistentStore -Name $ruleName -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -PolicyStore PersistentStore -Name $ruleName -DisplayName 'Raahat VPN - Allow sing-box server endpoint' -Group '{{FirewallGroup}}' -Direction Outbound -Action Allow -Profile Any -Enabled True -Program '{{engine}}' -Protocol TCP -RemoteAddress '{{endpoint}}' -RemotePort {{endpointPort}} | Out-Null
}
Write-Output 'ENDPOINT_ALLOWED'
""";
    }

    private async Task ApplyKillSwitchRulesAsync(
        string enginePath,
        IPAddress endpointAddress,
        int endpointPort)
    {
        var script = BuildFirewallLockScript(enginePath, endpointAddress, endpointPort);
        await RunPowerShellCommandAsync(script, TimeSpan.FromSeconds(25));
    }

    private async Task AddEngineEndpointRuleAsync(
        string enginePath,
        IPAddress endpointAddress,
        int endpointPort)
    {
        var script = BuildEndpointAllowScript(enginePath, endpointAddress, endpointPort);
        await RunPowerShellCommandAsync(script, TimeSpan.FromSeconds(15));
    }

    private static async Task<FirewallSnapshot> CaptureFirewallSnapshotAsync()
    {
        const string script = """
$ErrorActionPreference = 'Stop'
$names = @('Domain','Private','Public')
$effectiveProfiles = @(Get-NetFirewallProfile -PolicyStore ActiveStore -ErrorAction Stop)
$localProfiles = @(Get-NetFirewallProfile -PolicyStore PersistentStore -ErrorAction Stop)
foreach ($name in $names) {
    $effective = $effectiveProfiles | Where-Object { $_.Name -eq $name } | Select-Object -First 1
    $local = $localProfiles | Where-Object { $_.Name -eq $name } | Select-Object -First 1
    if (-not $effective -or -not $local) { throw 'A firewall profile is unavailable.' }
    if ($effective.Enabled -ne $true) { throw 'A Windows Firewall profile is disabled.' }
    if ($effective.AllowLocalFirewallRules -eq $false) { throw 'Local firewall rules are blocked by policy.' }
    if ([string]$effective.DefaultOutboundAction -notin @('Allow','Block')) { throw 'The effective outbound action is not locally manageable.' }
    if ([string]$local.DefaultOutboundAction -notin @('Allow','Block')) { throw 'The local outbound action cannot be restored safely.' }
    if ([string]$effective.DefaultOutboundAction -ne [string]$local.DefaultOutboundAction) { throw 'A policy overrides the local outbound action.' }
}

$activeAllows = @(Get-NetFirewallRule -PolicyStore ActiveStore -TracePolicyStore -Enabled True -Direction Outbound -Action Allow -ErrorAction Stop | Where-Object { $_.Group -ne 'RaahatVPN Kill Switch' })
$nonLocalAllows = @($activeAllows | Where-Object { [string]$_.PolicyStoreSourceType -ne 'Local' })
if ($nonLocalAllows.Count -gt 0) { throw 'Non-local outbound allow rules cannot be safely suspended.' }

$localAllows = @(Get-NetFirewallRule -PolicyStore PersistentStore -Enabled True -Direction Outbound -Action Allow -ErrorAction Stop | Where-Object { $_.Group -ne 'RaahatVPN Kill Switch' })
$profileSnapshot = @($localProfiles | Where-Object { $_.Name -in $names } | ForEach-Object {
    [pscustomobject]@{ Name = [string]$_.Name; DefaultOutboundAction = [string]$_.DefaultOutboundAction }
})
$ruleSnapshot = @($localAllows | ForEach-Object {
    [pscustomobject]@{ Name = [string]$_.Name; Enabled = ([string]$_.Enabled -eq 'True') }
})
$payload = [pscustomobject]@{ Profiles = $profileSnapshot; AllowRules = $ruleSnapshot }
Write-Output ('RAAHAT_SNAPSHOT:' + (ConvertTo-Json -InputObject $payload -Depth 8 -Compress))
""";

        var output = await RunPowerShellCommandAsync(script, TimeSpan.FromSeconds(20));
        var marker = output.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries)
            .LastOrDefault(line => line.StartsWith("RAAHAT_SNAPSHOT:", StringComparison.Ordinal));
        if (marker is null)
            throw new InvalidOperationException(
                "تنظیمات فعلی فایروال قابل خواندن نبود؛ Kill Switch بدون ذخیرهٔ نسخهٔ پشتیبان فعال نمی‌شود.");

        try
        {
            var payload = JsonSerializer.Deserialize<FirewallSnapshot>(
                marker["RAAHAT_SNAPSHOT:".Length..],
                new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
            if (payload is null || payload.Profiles.Length != 3)
                throw new JsonException();
            return payload;
        }
        catch (JsonException)
        {
            throw new InvalidOperationException(
                "نسخهٔ پشتیبان تنظیمات فایروال قابل تفسیر نبود؛ اتصال VPN شروع نشد.");
        }
    }

    private async Task WriteStateAsync(RecoveryState state)
    {
        await using var stateLock = await AcquireStateLockAsync(_rootDirectory);
        await WriteStateFileCoreAsync(_statePath, state);
    }

    private async Task UpdateStateAsync(Func<RecoveryState, RecoveryState> update)
    {
        await UpdateStateFileAsync(_rootDirectory, _statePath, update);
    }

    private static async Task UpdateStateFileAsync(
        string rootDirectory,
        string statePath,
        Func<RecoveryState, RecoveryState> update)
    {
        await using var stateLock = await AcquireStateLockAsync(rootDirectory);
        var current = await ReadStateFileCoreAsync(statePath);
        await WriteStateFileCoreAsync(statePath, update(current));
    }

    private static async Task WriteStateFileCoreAsync(string statePath, RecoveryState state)
    {
        var temporaryPath = statePath + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            var json = JsonSerializer.Serialize(state, new JsonSerializerOptions { WriteIndented = true });
            await File.WriteAllTextAsync(temporaryPath, json, new UTF8Encoding(false));
            File.Move(temporaryPath, statePath, overwrite: true);
        }
        finally
        {
            try { File.Delete(temporaryPath); } catch { }
        }
    }

    private static async Task<RecoveryState> ReadStateFileCoreAsync(string statePath)
    {
        var json = await File.ReadAllTextAsync(statePath);
        return JsonSerializer.Deserialize<RecoveryState>(
                   json,
                   new JsonSerializerOptions { PropertyNameCaseInsensitive = true }) ??
               throw new InvalidOperationException("فایل وضعیت بازیابی فایروال قابل خواندن نیست.");
    }

    private static async Task<FileStream> AcquireStateLockAsync(string rootDirectory)
    {
        Directory.CreateDirectory(rootDirectory);
        var lockPath = Path.Combine(rootDirectory, StateLockFileName);
        var deadline = DateTime.UtcNow.AddSeconds(20);

        while (true)
        {
            try
            {
                return new FileStream(
                    lockPath,
                    FileMode.OpenOrCreate,
                    FileAccess.ReadWrite,
                    FileShare.None,
                    1,
                    FileOptions.Asynchronous);
            }
            catch (IOException) when (DateTime.UtcNow < deadline)
            {
                await Task.Delay(100);
            }
            catch (IOException)
            {
                throw new InvalidOperationException(
                    "یک عملیات دیگر در حال بازیابی فایروال است؛ برای جلوگیری از تداخل، این عملیات متوقف شد.");
            }
        }
    }

    private async Task RegisterRecoveryTaskAsync()
    {
        var schtasksPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "schtasks.exe");
        var action = $"\"{PowerShellPath}\" -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"{_recoveryScriptPath}\"";
        var result = await RunExecutableAsync(schtasksPath, new[]
        {
            "/Create", "/SC", "MINUTE", "/MO", "1",
            "/TN", RecoveryTaskName,
            "/TR", action,
            "/RU", "SYSTEM",
            "/RL", "HIGHEST",
            "/F"
        }, TimeSpan.FromSeconds(20));
        if (result.ExitCode != 0)
            throw new InvalidOperationException("ثبت سرویس بازیابی خودکار فایروال در Task Scheduler ناموفق بود.");
    }

    private static async Task SecureDirectoryAsync(string directory)
    {
        var icacls = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "icacls.exe");
        var result = await RunExecutableAsync(icacls, new[]
        {
            directory,
            "/inheritance:r",
            "/grant:r",
            "*S-1-5-18:(OI)(CI)F",
            "*S-1-5-32-544:(OI)(CI)F",
            "/C"
        }, TimeSpan.FromSeconds(15));
        if (result.ExitCode != 0)
            throw new InvalidOperationException("محافظت دسترسی فایل بازیابی فایروال تنظیم نشد.");
    }

    private static async Task<string> RunPowerShellFileAsync(string scriptPath, TimeSpan timeout)
    {
        var result = await RunExecutableAsync(PowerShellPath, new[]
        {
            "-NoLogo", "-NoProfile", "-NonInteractive",
            "-ExecutionPolicy", "Bypass",
            "-File", scriptPath
        }, timeout);
        if (result.ExitCode != 0)
            throw new InvalidOperationException(
                "اسکریپت بازیابی نتوانست تنظیمات فایروال را کامل برگرداند. وظیفهٔ بازیابی خودکار تلاش را تکرار می‌کند.");
        return result.StandardOutput;
    }

    private static async Task<string> RunPowerShellCommandAsync(string command, TimeSpan timeout)
    {
        var result = await RunExecutableAsync(PowerShellPath, new[]
        {
            "-NoLogo", "-NoProfile", "-NonInteractive",
            "-ExecutionPolicy", "Bypass",
            "-Command", command
        }, timeout);
        if (result.ExitCode != 0)
            throw new InvalidOperationException(
                "Windows Firewall اجازهٔ اجرای امن Kill Switch را نداد. اتصال VPN شروع نشد.");
        return result.StandardOutput;
    }

    private static async Task<ProcessResult> RunExecutableAsync(
        string executable,
        IEnumerable<string> arguments,
        TimeSpan timeout)
    {
        using var process = new Process
        {
            StartInfo = new ProcessStartInfo
            {
                FileName = executable,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            }
        };
        foreach (var argument in arguments)
            process.StartInfo.ArgumentList.Add(argument);

        if (!process.Start())
            throw new InvalidOperationException("فرآیند مدیریت فایروال اجرا نشد.");

        var stdoutTask = process.StandardOutput.ReadToEndAsync();
        var stderrTask = process.StandardError.ReadToEndAsync();
        try
        {
            await process.WaitForExitAsync().WaitAsync(timeout);
        }
        catch (TimeoutException)
        {
            try { process.Kill(entireProcessTree: true); } catch { }
            throw new InvalidOperationException("عملیات مدیریت فایروال بیش از حد طول کشید.");
        }

        await Task.WhenAll(stdoutTask, stderrTask);
        return new ProcessResult(process.ExitCode, stdoutTask.Result, stderrTask.Result);
    }

    private static string EndpointRuleSuffix(IPAddress address, int port)
    {
        var bytes = SHA256.HashData(Encoding.UTF8.GetBytes(address + "|" + port));
        return Convert.ToHexString(bytes)[..16];
    }

    private static string EscapePowerShellLiteral(string value) => value.Replace("'", "''", StringComparison.Ordinal);

    private static bool IsPublicIpv4(IPAddress address)
    {
        var b = address.GetAddressBytes();
        if (b.Length != 4) return false;
        if (b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224) return false;
        if (b[0] == 100 && b[1] is >= 64 and <= 127) return false;
        if (b[0] == 169 && b[1] == 254) return false;
        if (b[0] == 172 && b[1] is >= 16 and <= 31) return false;
        if (b[0] == 192 && b[1] == 168) return false;
        if (b[0] == 192 && b[1] == 0 && b[2] == 2) return false;
        if (b[0] == 198 && b[1] is 18 or 19) return false;
        if (b[0] == 198 && b[1] == 51 && b[2] == 100) return false;
        if (b[0] == 203 && b[1] == 0 && b[2] == 113) return false;
        return true;
    }

    private static string PowerShellPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.System),
        "WindowsPowerShell",
        "v1.0",
        "powershell.exe");

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        try { DeactivateAsync().GetAwaiter().GetResult(); }
        catch { /* The SYSTEM recovery task keeps retrying while the state file remains. */ }
    }

    private sealed record ProcessResult(int ExitCode, string StandardOutput, string StandardError);
    private sealed record FirewallProfileState(string Name, string DefaultOutboundAction);
    private sealed record FirewallRuleState(string Name, bool Enabled);
    private sealed record FirewallSnapshot(FirewallProfileState[] Profiles, FirewallRuleState[] AllowRules);
    private sealed record RecoveryState(
        int OwnerPid,
        string OwnerExecutable,
        bool RestoreRequested,
        FirewallProfileState[] Profiles,
        FirewallRuleState[] AllowRules);

    private const string RecoveryScript = """
$ErrorActionPreference = 'Stop'
$statePath = Join-Path $PSScriptRoot 'state.json'
$group = 'RaahatVPN Kill Switch'
$taskName = 'RaahatVPN_KillSwitch_Recovery'
$lockPath = Join-Path $PSScriptRoot 'recovery.lock'
$recoveryLock = $null
$lockDeadline = [DateTime]::UtcNow.AddSeconds(20)
while ($null -eq $recoveryLock) {
    try {
        $recoveryLock = [System.IO.File]::Open(
            $lockPath,
            [System.IO.FileMode]::OpenOrCreate,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None)
    }
    catch [System.IO.IOException] {
        if ([DateTime]::UtcNow -ge $lockDeadline) {
            Write-Error 'Firewall recovery state is busy; retry is required.'
            exit 2
        }
        Start-Sleep -Milliseconds 100
    }
}
try {
    if (-not (Test-Path -LiteralPath $statePath)) {
        Remove-NetFirewallRule -PolicyStore PersistentStore -Group $group -ErrorAction SilentlyContinue | Out-Null
        schtasks.exe /Delete /TN $taskName /F 2>$null | Out-Null
        Write-Output 'CLEAN'
        exit 0
    }

    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    $ownerAlive = $false
    if (-not [bool]$state.RestoreRequested -and [int]$state.OwnerPid -gt 0) {
        $owner = Get-CimInstance -ClassName Win32_Process -Filter "ProcessId = $([int]$state.OwnerPid)" -ErrorAction SilentlyContinue
        if ($owner -and $owner.ExecutablePath -and
            [string]::Equals([string]$owner.ExecutablePath, [string]$state.OwnerExecutable, [StringComparison]::OrdinalIgnoreCase)) {
            $ownerAlive = $true
        }
    }

    if (-not [bool]$state.RestoreRequested) {
        # Keep the outbound lock while the app is alive and after an
        # unexpected exit/reboot. Also capture and disable new local allow
        # rules introduced by other software while Raahat is running.
        $savedRules = @($state.AllowRules)
        $knownNames = @($savedRules | ForEach-Object { [string]$_.Name })
        $snapshotChanged = $false
        $newAllows = @(Get-NetFirewallRule -PolicyStore PersistentStore -Enabled True -Direction Outbound -Action Allow -ErrorAction Stop | Where-Object { $_.Group -ne $group })
        foreach ($rule in $newAllows) {
            $ruleName = [string]$rule.Name
            if ($knownNames -notcontains $ruleName) {
                $savedRules += [pscustomobject]@{ Name = $ruleName; Enabled = $true }
                $knownNames += $ruleName
                $snapshotChanged = $true
            }
            Disable-NetFirewallRule -PolicyStore PersistentStore -Name $ruleName -ErrorAction Stop | Out-Null
        }

        if ($snapshotChanged) {
            $state.AllowRules = $savedRules
            $snapshotJson = ConvertTo-Json -InputObject $state -Depth 8
            [System.IO.File]::WriteAllText(
                $statePath,
                $snapshotJson,
                [System.Text.UTF8Encoding]::new($false))
        }

        foreach ($profile in @($state.Profiles)) {
            Set-NetFirewallProfile -PolicyStore PersistentStore -Name ([string]$profile.Name) -Enabled True -DefaultOutboundAction Block -ErrorAction Stop
        }
        $effective = @(Get-NetFirewallProfile -PolicyStore ActiveStore)
        if (@($effective | Where-Object { [string]$_.DefaultOutboundAction -ne 'Block' -or $_.Enabled -ne $true }).Count -gt 0) {
            throw 'Outbound firewall lockdown could not be maintained.'
        }

        if ($ownerAlive) {
            Write-Output 'ACTIVE'
        } else {
            Write-Output 'LOCKED_RECOVERY_REQUIRED'
        }
        exit 0
    }

    # Restore only after an explicit normal disconnect or a new Raahat startup.
    foreach ($profile in @($state.Profiles)) {
        Set-NetFirewallProfile -PolicyStore PersistentStore -Name ([string]$profile.Name) -DefaultOutboundAction ([string]$profile.DefaultOutboundAction) -ErrorAction Stop
    }
    foreach ($savedRule in @($state.AllowRules)) {
        $rule = Get-NetFirewallRule -PolicyStore PersistentStore -Name ([string]$savedRule.Name) -ErrorAction SilentlyContinue
        if ($rule) {
            if ([bool]$savedRule.Enabled) {
                Enable-NetFirewallRule -InputObject $rule -ErrorAction Stop | Out-Null
            } else {
                Disable-NetFirewallRule -InputObject $rule -ErrorAction Stop | Out-Null
            }
        }
    }

    Remove-NetFirewallRule -PolicyStore PersistentStore -Group $group -ErrorAction SilentlyContinue | Out-Null
    Remove-Item -LiteralPath $statePath -Force
    schtasks.exe /Delete /TN $taskName /F 2>$null | Out-Null
    Write-Output 'RESTORED'
    exit 0
}
catch {
    # The task is scheduled every minute; retain the snapshot so a transient
    # firewall or Task Scheduler error is retried rather than losing rollback data.
    Write-Error 'Raahat VPN firewall recovery failed; retry is required.'
    exit 2
}
""";
}
