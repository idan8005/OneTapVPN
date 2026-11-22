using System;
using System.Windows;
using System.Windows.Media;

namespace RaahatVPN;

public partial class MainWindow : Window
{
    private readonly WindowsTunnelController _tunnel = new();

    public MainWindow()
    {
        InitializeComponent();
        _tunnel.EngineExited += Tunnel_EngineExited;
        Closed += (_, _) => _tunnel.Dispose();
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
                ? "خروجی VPN تأیید شد"
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
            ConnectButton.IsEnabled = !running;
            DisconnectButton.IsEnabled = running;
        }
    }

    private async void DisconnectButton_Click(object sender, RoutedEventArgs e)
    {
        DisconnectButton.IsEnabled = false;
        try
        {
            await _tunnel.StopAsync();
            StatusTextBlock.Text = "قطع است";
            StatusTextBlock.Foreground = Brushes.White;
            StatusDetailTextBlock.Text = "تونل VPN متوقف شد.";
            ExitIpTextBlock.Text = string.Empty;
        }
        catch
        {
            StatusTextBlock.Text = "وضعیت قطع اتصال نامشخص است";
            StatusTextBlock.Foreground = Brushes.Orange;
            StatusDetailTextBlock.Text = "برنامه نتوانست توقف موتور را تأیید کند. پنجره را نبندید و وضعیت را دوباره بررسی کنید.";
        }
        finally
        {
            ConnectButton.IsEnabled = !_tunnel.IsRunning;
            DisconnectButton.IsEnabled = _tunnel.IsRunning;
        }
    }

    private void Tunnel_EngineExited(object? sender, EventArgs e)
    {
        _ = Dispatcher.InvokeAsync(() =>
        {
            if (!IsLoaded || !IsVisible) return;
            StatusTextBlock.Text = "موتور VPN متوقف شد";
            StatusTextBlock.Foreground = Brushes.Orange;
            StatusDetailTextBlock.Text =
                "فرآیند تونل متوقف شده است؛ اتصال اینترنت از طریق VPN دیگر تأیید نمی‌شود.";
            ExitIpTextBlock.Text = string.Empty;
            ConnectButton.IsEnabled = true;
            DisconnectButton.IsEnabled = false;
        });
    }
}
