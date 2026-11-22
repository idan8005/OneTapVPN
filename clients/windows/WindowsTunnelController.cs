using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using Microsoft.Win32.SafeHandles;

namespace RaahatVPN;

public sealed record TunnelConnectResult(
    bool EngineRunning,
    bool EgressVerified,
    string? OriginalPublicIp,
    string? TunnelPublicIp,
    string Message);

public sealed class WindowsTunnelController : IDisposable
{
    private readonly HttpClient _httpClient = new(
        new SocketsHttpHandler
        {
            UseProxy = false,
            ConnectTimeout = TimeSpan.FromSeconds(5)
        })
    {
        Timeout = TimeSpan.FromSeconds(7)
    };

    private Process? _process;
    private SafeFileHandle? _jobHandle;
    private FileStream? _configLease;
    private WindowsKillSwitch? _killSwitch;
    private string? _baselinePublicIp;
    private bool _disposed;

    public event EventHandler? EngineExited;

    public bool KillSwitchActive => _killSwitch?.IsActive == true;

    public bool IsRunning
    {
        get
        {
            try { return _process is { HasExited: false }; }
            catch (InvalidOperationException) { return false; }
        }
    }

    /// <summary>
    /// Resolves a server before the kill switch is armed. The generated
    /// sing-box config then uses this numeric endpoint while retaining the
    /// original TLS SNI, so DNS is not required on the physical adapter.
    /// </summary>
    public async Task<IPAddress> ResolveServerAddressAsync(string vlessLink)
    {
        var profile = VlessRealityProfile.Parse(vlessLink);
        IPAddress[] addresses;
        try
        {
            if (IPAddress.TryParse(profile.Server, out var literal))
                addresses = new[] { literal };
            else
                addresses = await Dns.GetHostAddressesAsync(profile.Server)
                    .WaitAsync(TimeSpan.FromSeconds(8));
        }
        catch (Exception error) when (
            error is SocketException or TimeoutException or System.ComponentModel.Win32Exception)
        {
            throw new InvalidOperationException(
                "نشانی سرور پیش از فعال‌سازی Kill Switch قابل تبدیل به IP نبود.");
        }

        var selected = addresses.FirstOrDefault(IsPublicIpv4);
        if (selected is null)
            throw new InvalidOperationException(
                "سرور به IP عمومی IPv4 معتبر تبدیل نشد؛ برای جلوگیری از مجاز کردن مقصد ناشناس، اتصال رد شد.");
        return selected;
    }

    public async Task<TunnelConnectResult> ConnectAsync(string vlessLink)
    {
        var endpoint = await ResolveServerAddressAsync(vlessLink);
        return await ConnectResolvedAsync(vlessLink, endpoint);
    }

    internal async Task<TunnelConnectResult> ConnectResolvedAsync(
        string vlessLink,
        IPAddress resolvedEndpoint)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (IsRunning)
            throw new InvalidOperationException("اتصال از قبل فعال است.");
        if (_process is not null)
            await StopEngineOnlyAsync();

        var profile = VlessRealityProfile.Parse(vlessLink);
        if (!IsPublicIpv4(resolvedEndpoint))
            throw new InvalidOperationException("IP مقصد سرور برای قانون فایروال معتبر نیست.");

        var enginePath = Path.Combine(AppContext.BaseDirectory, "Core", "sing-box.exe");
        if (!File.Exists(enginePath))
            throw new FileNotFoundException(
                "موتور VPN همراه برنامه پیدا نشد. بسته کامل Raahat VPN را دوباره دریافت کنید.",
                enginePath);

        // Capture the baseline before the first firewall lock and reuse it
        // across failover attempts while the kill switch remains armed.
        var baselineIp = KillSwitchActive
            ? _baselinePublicIp
            : await TryGetPublicIpv4Async();
        if (string.IsNullOrWhiteSpace(baselineIp))
            throw new InvalidOperationException(
                "IP عمومی پیش از اتصال قابل تأیید نبود؛ برای جلوگیری از نمایش اتصال تأییدنشده، Kill Switch فعال نشد.");

        var runtimeDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "RaahatVPN",
            "Runtime");
        Directory.CreateDirectory(runtimeDirectory);

        var configPath = Path.Combine(runtimeDirectory, $"session-{Guid.NewGuid():N}.json");
        FileStream? configLease = null;
        Process? engine = null;
        SafeFileHandle? job = null;

        try
        {
            configLease = new FileStream(
                configPath,
                FileMode.CreateNew,
                FileAccess.ReadWrite,
                FileShare.Read | FileShare.Delete,
                4096,
                FileOptions.DeleteOnClose);
            var configBytes = System.Text.Encoding.UTF8.GetBytes(
                profile.ToSingBoxConfig(resolvedEndpoint.ToString()));
            await configLease.WriteAsync(configBytes);
            await configLease.FlushAsync();
            configLease.Flush(flushToDisk: true);

            await ValidateConfigAsync(enginePath, configPath);

            _killSwitch ??= new WindowsKillSwitch();
            await _killSwitch.EnsureActiveAsync(enginePath, resolvedEndpoint, profile.ServerPort);
            _baselinePublicIp ??= baselineIp;

            engine = new Process
            {
                StartInfo = new ProcessStartInfo
                {
                    FileName = enginePath,
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true
                },
                EnableRaisingEvents = true
            };
            engine.StartInfo.ArgumentList.Add("run");
            engine.StartInfo.ArgumentList.Add("-c");
            engine.StartInfo.ArgumentList.Add(configPath);

            if (!engine.Start())
                throw new InvalidOperationException("موتور VPN اجرا نشد.");

            job = WindowsJob.AttachKillOnClose(engine);
            engine.Exited += (_, _) => HandleEngineExited(engine);
            engine.OutputDataReceived += (_, _) => { };
            engine.ErrorDataReceived += (_, _) => { };
            engine.BeginOutputReadLine();
            engine.BeginErrorReadLine();

            _process = engine;
            _jobHandle = job;
            _configLease = configLease;

            await Task.Delay(TimeSpan.FromSeconds(2));
            if (engine.HasExited)
                throw new InvalidOperationException(
                    "موتور VPN بلافاصله متوقف شد. Kill Switch فعال مانده و از خروج ترافیک جلوگیری می‌کند.");

            var tunnelIp = await VerifyTunnelEgressAsync(baselineIp);
            if (tunnelIp is not null && baselineIp is not null && tunnelIp != baselineIp)
            {
                return new TunnelConnectResult(
                    true,
                    true,
                    baselineIp,
                    tunnelIp,
                    "خروجی عمومی IPv4 از طریق تونل تغییر کرده است؛ Kill Switch ویندوز نیز فعال است و در صورت قطع تونل، ترافیک خروجی را مسدود می‌کند.");
            }

            if (tunnelIp is not null)
            {
                return new TunnelConnectResult(
                    true,
                    false,
                    baselineIp,
                    tunnelIp,
                    "موتور فعال است، اما تغییر مسیر خروجی اینترنت تأیید نشد. Kill Switch فعال مانده و اینترنت تا قطع اتصال مسدود است.");
            }

            return new TunnelConnectResult(
                true,
                false,
                baselineIp,
                null,
                "موتور فعال است، اما بررسی مستقل HTTPS موفق نشد. Kill Switch فعال مانده و اینترنت تا قطع اتصال مسدود است.");
        }
        catch
        {
            if (engine is not null)
            {
                try
                {
                    if (!engine.HasExited)
                        engine.Kill(entireProcessTree: true);
                    await engine.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(5));
                }
                catch { }
            }

            job?.Dispose();
            engine?.Dispose();
            configLease?.Dispose();

            if (ReferenceEquals(_process, engine))
                _process = null;
            if (ReferenceEquals(_jobHandle, job))
                _jobHandle = null;
            if (ReferenceEquals(_configLease, configLease))
                _configLease = null;
            throw;
        }
    }

    /// <summary>
    /// During failover the kill switch is kept armed between candidates so
    /// there is no unprotected gap.
    /// </summary>
    public async Task StopAsync(bool preserveKillSwitch = false)
    {
        await StopEngineOnlyAsync();

        if (!preserveKillSwitch && _killSwitch is not null)
        {
            await _killSwitch.DeactivateAsync();
            _killSwitch.Dispose();
            _killSwitch = null;
            _baselinePublicIp = null;
        }
    }

    private async Task StopEngineOnlyAsync()
    {
        var process = _process;
        if (process is not null)
        {
            try
            {
                if (!process.HasExited)
                {
                    process.Kill(entireProcessTree: true);
                    await process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(8));
                }
            }
            catch (InvalidOperationException) { }
            catch (System.ComponentModel.Win32Exception) when (SafeHasExited(process)) { }
            catch (TimeoutException) { }

            if (!SafeHasExited(process))
            {
                _jobHandle?.Dispose();
                _jobHandle = null;
                try { await process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(5)); }
                catch (TimeoutException) { }
            }

            if (!SafeHasExited(process))
                throw new InvalidOperationException(
                    "توقف موتور VPN تأیید نشد؛ برای جلوگیری از نشت، Kill Switch خاموش نمی‌شود.");
        }

        _jobHandle?.Dispose();
        _jobHandle = null;
        _configLease?.Dispose();
        _configLease = null;
        _process?.Dispose();
        _process = null;
    }

    private void HandleEngineExited(Process engine)
    {
        if (ReferenceEquals(_process, engine))
        {
            _jobHandle?.Dispose();
            _jobHandle = null;
            _configLease?.Dispose();
            _configLease = null;
        }
        EngineExited?.Invoke(this, EventArgs.Empty);
    }

    private async Task ValidateConfigAsync(string enginePath, string configPath)
    {
        using var checker = new Process
        {
            StartInfo = new ProcessStartInfo
            {
                FileName = enginePath,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            }
        };
        checker.StartInfo.ArgumentList.Add("check");
        checker.StartInfo.ArgumentList.Add("-c");
        checker.StartInfo.ArgumentList.Add(configPath);

        if (!checker.Start())
            throw new InvalidOperationException("اعتبارسنجی پیکربندی موتور VPN شروع نشد.");

        var stdoutTask = checker.StandardOutput.ReadToEndAsync();
        var stderrTask = checker.StandardError.ReadToEndAsync();
        try
        {
            await checker.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(15));
        }
        catch (TimeoutException)
        {
            try { checker.Kill(entireProcessTree: true); } catch { }
            throw new InvalidOperationException("اعتبارسنجی پیکربندی موتور VPN بیش از حد طول کشید.");
        }

        await Task.WhenAll(stdoutTask, stderrTask);
        if (checker.ExitCode != 0)
            throw new InvalidOperationException(
                "موتور VPN پیکربندی این سرور را نپذیرفت. برای حفظ محرمانگی، جزئیات شامل اطلاعات سرور ذخیره یا نمایش داده نشد.");
    }

    private async Task<string?> VerifyTunnelEgressAsync(string? originalIp)
    {
        for (var attempt = 0; attempt < 5; attempt++)
        {
            if (!IsRunning)
                return null;

            var currentIp = await TryGetPublicIpv4Async();
            if (currentIp is not null && (originalIp is null || currentIp != originalIp))
                return currentIp;

            await Task.Delay(TimeSpan.FromSeconds(2));
        }

        return await TryGetPublicIpv4Async();
    }

    private async Task<string?> TryGetPublicIpv4Async()
    {
        try
        {
            using var response = await _httpClient.GetAsync("https://api.ipify.org");
            response.EnsureSuccessStatusCode();
            var text = (await response.Content.ReadAsStringAsync()).Trim();
            if (!IPAddress.TryParse(text, out var address) ||
                address.AddressFamily != AddressFamily.InterNetwork ||
                !IsPublicIpv4(address))
                return null;
            return address.ToString();
        }
        catch (Exception ex) when (
            ex is HttpRequestException or TaskCanceledException or InvalidOperationException)
        {
            return null;
        }
    }

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

    private static bool SafeHasExited(Process process)
    {
        try { return process.HasExited; }
        catch (InvalidOperationException) { return true; }
    }

    public void Dispose()
    {
        if (_disposed) return;
        try { StopAsync().GetAwaiter().GetResult(); }
        catch
        {
            try { _killSwitch?.RequestRecoveryAsync().GetAwaiter().GetResult(); } catch { }
        }
        _killSwitch?.Dispose();
        _killSwitch = null;
        _disposed = true;
        _httpClient.Dispose();
    }
}

internal static class WindowsJob
{
    private const int JobObjectExtendedLimitInformation = 9;
    private const uint JobObjectLimitKillOnJobClose = 0x00002000;

    public static SafeFileHandle AttachKillOnClose(Process process)
    {
        var job = CreateJobObjectW(IntPtr.Zero, null);
        if (job.IsInvalid)
            throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());

        var limits = new ExtendedLimitInformation();
        limits.BasicLimitInformation.LimitFlags = JobObjectLimitKillOnJobClose;

        if (!SetInformationJobObject(
                job,
                JobObjectExtendedLimitInformation,
                ref limits,
                (uint)Marshal.SizeOf<ExtendedLimitInformation>()))
        {
            var error = Marshal.GetLastWin32Error();
            job.Dispose();
            throw new System.ComponentModel.Win32Exception(error);
        }

        if (!AssignProcessToJobObject(job, process.Handle))
        {
            var error = Marshal.GetLastWin32Error();
            job.Dispose();
            throw new System.ComponentModel.Win32Exception(error);
        }

        return job;
    }

    [DllImport("kernel32.dll", EntryPoint = "CreateJobObjectW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern SafeFileHandle CreateJobObjectW(IntPtr jobAttributes, string? name);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetInformationJobObject(
        SafeFileHandle job,
        int informationClass,
        ref ExtendedLimitInformation information,
        uint informationLength);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AssignProcessToJobObject(SafeFileHandle job, IntPtr process);

    [StructLayout(LayoutKind.Sequential)]
    private struct BasicLimitInformation
    {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize;
        public UIntPtr MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public UIntPtr Affinity;
        public uint PriorityClass;
        public uint SchedulingClass;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct IoCounters
    {
        public ulong ReadOperationCount;
        public ulong WriteOperationCount;
        public ulong OtherOperationCount;
        public ulong ReadTransferCount;
        public ulong WriteTransferCount;
        public ulong OtherTransferCount;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ExtendedLimitInformation
    {
        public BasicLimitInformation BasicLimitInformation;
        public IoCounters IoInfo;
        public UIntPtr ProcessMemoryLimit;
        public UIntPtr JobMemoryLimit;
        public UIntPtr PeakProcessMemoryUsed;
        public UIntPtr PeakJobMemoryUsed;
    }
}
