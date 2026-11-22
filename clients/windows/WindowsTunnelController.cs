using System;
using System.Diagnostics;
using System.IO;
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
    private bool _disposed;

    public event EventHandler? EngineExited;

    public bool IsRunning
    {
        get
        {
            try { return _process is { HasExited: false }; }
            catch (InvalidOperationException) { return false; }
        }
    }

    public async Task<TunnelConnectResult> ConnectAsync(string vlessLink)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (IsRunning)
            throw new InvalidOperationException("اتصال از قبل فعال است.");
        if (_process is not null)
            await StopAsync();

        var profile = VlessRealityProfile.Parse(vlessLink);
        var enginePath = Path.Combine(AppContext.BaseDirectory, "Core", "sing-box.exe");
        if (!File.Exists(enginePath))
            throw new FileNotFoundException(
                "موتور VPN همراه برنامه پیدا نشد. بسته کامل Raahat VPN را دوباره دریافت کنید.",
                enginePath);

        // Read the baseline before enabling TUN so the app can verify that public IPv4 egress changes.
        var baselineIp = await TryGetPublicIpv4Async();

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
            // The config contains the VLESS UUID and Reality key. It is held with a delete-on-close
            // handle and is removed when the app exits the engine, including abrupt app termination.
            configLease = new FileStream(
                configPath,
                FileMode.CreateNew,
                FileAccess.ReadWrite,
                FileShare.Read | FileShare.Delete,
                4096,
                FileOptions.DeleteOnClose);
            var configBytes = System.Text.Encoding.UTF8.GetBytes(profile.ToSingBoxConfig());
            await configLease.WriteAsync(configBytes);
            await configLease.FlushAsync();
            configLease.Flush(flushToDisk: true);

            await ValidateConfigAsync(enginePath, configPath);

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
            engine.OutputDataReceived += (_, _) => { }; // Never write engine output or connection data to logs.
            engine.ErrorDataReceived += (_, _) => { };  // Errors are intentionally not persisted.
            engine.BeginOutputReadLine();
            engine.BeginErrorReadLine();

            _process = engine;
            _jobHandle = job;
            _configLease = configLease;

            await Task.Delay(TimeSpan.FromSeconds(2));
            if (engine.HasExited)
                throw new InvalidOperationException(
                    "موتور VPN بلافاصله متوقف شد. ممکن است درایور TUN یا تنظیمات شبکه در این ویندوز پشتیبانی نشود.");

            var tunnelIp = await VerifyTunnelEgressAsync(baselineIp);
            if (tunnelIp is not null && baselineIp is not null && tunnelIp != baselineIp)
            {
                return new TunnelConnectResult(
                    true,
                    true,
                    baselineIp,
                    tunnelIp,
                    "خروجی عمومی IPv4 از طریق تونل تغییر کرده و بررسی HTTPS موفق بوده است.");
            }

            if (tunnelIp is not null)
            {
                return new TunnelConnectResult(
                    true,
                    false,
                    baselineIp,
                    tunnelIp,
                    "موتور فعال است، اما تغییر مسیر خروجی اینترنت تأیید نشد. اتصال را تأییدشده در نظر نگیرید.");
            }

            return new TunnelConnectResult(
                true,
                false,
                baselineIp,
                null,
                "موتور فعال است، اما بررسی مستقل HTTPS موفق نشد. اتصال هنوز تأیید نشده است.");
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
                catch { /* Best-effort cleanup; the job handle below is the final safety net. */ }
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

    public async Task StopAsync()
    {
        var process = _process;
        try
        {
            if (process is { HasExited: false })
            {
                process.Kill(entireProcessTree: true);
                await process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(8));
            }
        }
        catch (InvalidOperationException) { }
        catch (System.ComponentModel.Win32Exception) { }
        catch (TimeoutException) { }

        // Closing a Windows Job Object with KILL_ON_JOB_CLOSE also terminates any child process.
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
            if (currentIp is not null)
            {
                if (originalIp is null || currentIp != originalIp)
                    return currentIp;
            }

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

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        try
        {
            var process = _process;
            if (process is { HasExited: false })
                process.Kill(entireProcessTree: true);
        }
        catch { }
        _jobHandle?.Dispose();
        _configLease?.Dispose();
        _process?.Dispose();
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
