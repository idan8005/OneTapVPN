using System;
using System.Windows;
using System.Windows.Media;

namespace RaahatVPN;

public partial class MainWindow : Window
{
    private readonly WindowsTunnelController _tunnel = new();
    private bool _startupRecoveryPending;

    public MainWindow()
    {
        InitializeComponent();
        _tunnel.EngineExited += Tunnel_EngineExited;
        Loaded += MainWindow_Loaded;
        Closed += (_, _) => _tunnel.Dispose();
    }


    private async void MainWindow_Loaded(object sender, RoutedEventArgs e)
    {
        ConnectButton.IsEnabled = false;
        StatusTextBlock.Text = "در حال بررسی وضعیت فایروال...";
        StatusTextBlock.Foreground = Brushes.White;
        StatusDetailTextBlock.Text = "اگر اجرای قبلی ناگهانی بسته شده باشد، تنظیمات شبکه پیش از اتصال جدید بازیابی می‌شوند.";

        try
        {
            await WindowsKillSwitch.RecoverExistingStateAsync();
            _startupRecoveryPending = false;
            DisconnectButton.Content = "قطع اتصال";
            DisconnectButton.IsEnabled = false;
            StatusTextBlock.Text = "قطع است";
            StatusDetailTextBlock.Text = "آماده اتصال هستید. Kill Switch هنگام اتصال فعال می‌شود.";
            ConnectButton.IsEnabled = true;
        }
        catch (Exception ex)
        {
            _startupRecoveryPending = true;
            StatusTextBlock.Text = "بازیابی فایروال لازم است";
            StatusTextBlock.Foreground = Brushes.Orange;
            StatusDetailTextBlock.Text = ex.Message + " برای تلاش دوباره، دکمهٔ بازیابی را بزنید.";
            ConnectButton.IsEnabled = false;
            DisconnectButton.Content = "بازیابی فایروال";
            DisconnectButton.IsEnabled = true;
        }
    }

    private async void ConnectButton_Click(object sender, RoutedEventArgs e)
    {
        ConnectButton.IsEnabled = false;
        DisconnectButton.IsEnabled = false;
        StatusTextBlock.Text = "در حال آماده‌سازی اتصال...";
        StatusTextBlock.Foreground = Brushes.White;
        StatusDetailTextBlock.Text = string.Empty;
        ExitIpTextBlock.Text = string.Empty;

        try
        {
            var candidateText = ServerLinkTextBox.Text;
            if (string.IsNullOrWhiteSpace(candidateText))
            {
                StatusTextBlock.Text = "در حال دریافت سرورهای رایگان...";
                StatusDetailTextBlock.Text =
                    "فهرست عمومی دریافت می‌شود و فقط لینک‌های سازگار با VLESS + REALITY + TCP نگه داشته می‌شوند.";

                using var feedClient = new PublicVlessFeedClient();
                var candidates = await feedClient.FetchCandidatesAsync();
                candidateText = string.Join(Environment.NewLine, candidates);

                StatusDetailTextBlock.Text =
                    candidates.Count.ToString() +
                    " سرور سازگار دریافت شد؛ اتصال هرکدام روی همین دستگاه بررسی می‌شود.";
            }

            StatusTextBlock.Text = "در حال آزمایش و اتصال...";
            StatusDetailTextBlock.Text =
                "سرورها به‌ترتیب امتحان می‌شوند؛ فقط خروجی واقعی IPv4 که از تونل عبور کند پذیرفته می‌شود.";

            var result = await _tunnel.ConnectBestAsync(candidateText);
            StatusTextBlock.Text = result.EgressVerified
                ? "اتصال برقرار شد · Kill Switch فعال"
                : "تونل فعال؛ اتصال تأییدنشده";
            StatusTextBlock.Foreground = result.EgressVerified
                ? Brushes.LightGreen
                : Brushes.Khaki;
            StatusDetailTextBlock.Text = result.Message;

            if (result.OriginalPublicIp is not null && result.TunnelPublicIp is not null)
            {
                ExitIpTextBlock.Text =
                    "IP قبل: " + result.OriginalPublicIp + "    ←    IP تونل: " + result.TunnelPublicIp;
            }
            else if (result.TunnelPublicIp is not null)
            {
                ExitIpTextBlock.Text = "IP خروجی فعلی: " + result.TunnelPublicIp;
            }
        }
        catch (Exception ex)
        {
            StatusTextBlock.Text = "اتصال برقرار نشد";
            StatusTextBlock.Foreground = Brushes.Orange;
            StatusDetailTextBlock.Text = ex.Message;
            ExitIpTextBlock.Text = string.Empty;
        }
        finally
        {
            var running = _tunnel.IsRunning;
            ConnectButton.IsEnabled = !running && !_tunnel.KillSwitchActive;
            DisconnectButton.IsEnabled = running || _tunnel.KillSwitchActive;
        }
    }

    private async void DisconnectButton_Click(object sender, RoutedEventArgs e)
    {
        DisconnectButton.IsEnabled = false;
        try
        {
            if (_startupRecoveryPending)
            {
                await WindowsKillSwitch.RecoverExistingStateAsync();
                _startupRecoveryPending = false;
                StatusTextBlock.Text = "تنظیمات قبلی فایروال بازیابی شد";
                StatusTextBlock.Foreground = Brushes.White;
                StatusDetailTextBlock.Text = "اتصال VPN قطع است و اینترنت به وضعیت قبلی ویندوز برگشته است.";
                ExitIpTextBlock.Text = string.Empty;
                DisconnectButton.Content = "قطع اتصال";
            }
            else
            {
                await _tunnel.StopAsync();
                StatusTextBlock.Text = "قطع است";
                StatusTextBlock.Foreground = Brushes.White;
                StatusDetailTextBlock.Text = "تونل VPN متوقف شد و تنظیمات قبلی فایروال بازیابی شدند.";
                ExitIpTextBlock.Text = string.Empty;
            }
        }
        catch (Exception ex)
        {
            StatusTextBlock.Text = "بازیابی فایروال کامل نشد";
            StatusTextBlock.Foreground = Brushes.Orange;
            StatusDetailTextBlock.Text =
                ex.Message + " اینترنت برای جلوگیری از نشت مسدود می‌ماند؛ دوباره تلاش کنید.";
        }
        finally
        {
            if (_startupRecoveryPending)
            {
                ConnectButton.IsEnabled = false;
                DisconnectButton.Content = "بازیابی فایروال";
                DisconnectButton.IsEnabled = true;
            }
            else
            {
                ConnectButton.IsEnabled = !_tunnel.IsRunning && !_tunnel.KillSwitchActive;
                DisconnectButton.Content = "قطع اتصال";
                DisconnectButton.IsEnabled = _tunnel.IsRunning || _tunnel.KillSwitchActive;
            }
        }
    }

    private void Tunnel_EngineExited(object? sender, EventArgs e)
    {
        _ = Dispatcher.InvokeAsync(() =>
        {
            if (!IsLoaded || !IsVisible) return;
            StatusTextBlock.Text = "تونل قطع شد · Kill Switch فعال";
            StatusTextBlock.Foreground = Brushes.Orange;
            StatusDetailTextBlock.Text =
                "موتور VPN متوقف شده است. برای جلوگیری از نشت، اینترنت همچنان مسدود است؛ دکمهٔ قطع اتصال را بزنید تا تنظیمات قبلی فایروال بازیابی شود.";
            ExitIpTextBlock.Text = string.Empty;
            ConnectButton.IsEnabled = false;
            DisconnectButton.IsEnabled = true;
        });
    }
}
