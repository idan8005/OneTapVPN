using System.Diagnostics;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Principal;
using System.IO.Compression;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;

namespace RaahatVPN.Desktop;

public sealed record HybridServer(string Id,string Name,string Country,string Region,string Host,int Port,string Protocol,string Config,bool Enabled)
{ public string DisplayName => $"{Name} · {Country} · {Host}:{Port}"; }
public sealed record HybridCatalog(string Plan,string GeneratedAt,bool PublicDiscovery,List<HybridServer> PremiumServers);

internal sealed class TokenVault
{
    private readonly string _path=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"RaahatVPN","account.dat");
    private static readonly byte[] Entropy=Encoding.UTF8.GetBytes("RaahatVPN.Windows.Account.v1");
    public void Save(string token)
    {
        if(string.IsNullOrWhiteSpace(token)||token.Length>4096) throw new ArgumentException("کد دسترسی معتبر نیست.");
        Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
        var plain=Encoding.UTF8.GetBytes(token.Trim());
        var cipher=ProtectedData.Protect(plain,Entropy,DataProtectionScope.CurrentUser);
        var temp=_path+".tmp"; File.WriteAllBytes(temp,cipher); File.Move(temp,_path,true);
        CryptographicOperations.ZeroMemory(plain);
    }
    public string? Read()
    {
        try {
            if(!File.Exists(_path)) return null;
            var plain=ProtectedData.Unprotect(File.ReadAllBytes(_path),Entropy,DataProtectionScope.CurrentUser);
            try { return Encoding.UTF8.GetString(plain); } finally { CryptographicOperations.ZeroMemory(plain); }
        } catch(CryptographicException) { Clear(); return null; } catch(IOException) { return null; }
    }
    public void Clear() { try { if(File.Exists(_path)) File.Delete(_path); } catch(IOException) { } }
}

internal sealed class HybridApi : IDisposable
{
    private readonly string _base;
    private readonly HttpClient _http=new(new HttpClientHandler { AllowAutoRedirect=false, UseCookies=false }) { Timeout=TimeSpan.FromSeconds(15) };
    public HybridApi(string address)
    {
        if(!Uri.TryCreate(address.TrimEnd('/'),UriKind.Absolute,out var uri)||uri.Scheme!="https"||string.IsNullOrWhiteSpace(uri.Host)||!string.IsNullOrEmpty(uri.UserInfo)||!string.IsNullOrEmpty(uri.Query)||!string.IsNullOrEmpty(uri.Fragment))
            throw new ArgumentException("نشانی سرویس مرکزی باید HTTPS معتبر باشد.");
        _base=uri.ToString().TrimEnd('/');
    }
    public async Task<HybridCatalog> Fetch(string token)
    {
        using var request=new HttpRequestMessage(HttpMethod.Get,_base+"/api/v1/catalog");
        request.Headers.Authorization=new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer",token);
        request.Headers.Accept.ParseAdd("application/json");
        using var response=await _http.SendAsync(request,HttpCompletionOption.ResponseHeadersRead);
        if(response.StatusCode!=HttpStatusCode.OK) throw new HttpRequestException((int)response.StatusCode switch {
            401=>"کد دسترسی اشتباه یا منقضی شده است.",403=>"این حساب اجازه استفاده از سرورهای ویژه را ندارد.",503=>"سرویس ویژه موقتاً در دسترس نیست.",_=>$"خطای سرویس مرکزی: HTTP {(int)response.StatusCode}."});
        var result=await response.Content.ReadFromJsonAsync<HybridCatalog>(new JsonSerializerOptions{PropertyNameCaseInsensitive=true})??throw new InvalidDataException("پاسخ سرویس مرکزی نامعتبر است.");
        if(result.Plan is not ("free" or "premium")) throw new InvalidDataException("طرح حساب نامعتبر است.");
        return result with { PremiumServers=result.PremiumServers.Where(x=>x.Enabled&&x.Protocol=="vless-reality"&&!string.IsNullOrWhiteSpace(x.Config)&&x.Port is >=1 and <=65535).Take(60).ToList() };
    }
    public void Dispose()=>_http.Dispose();
}

internal static class VlessConfig
{
    public static string Build(string link)
    {
        if(!Uri.TryCreate(link.Trim(),UriKind.Absolute,out var u)||u.Scheme!="vless") throw new FormatException("پروفایل VLESS معتبر نیست.");
        var uuid=Uri.UnescapeDataString(u.UserInfo);
        if(!Guid.TryParse(uuid,out _)) throw new FormatException("UUID معتبر نیست.");
        var q=new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase);
        foreach(var part in u.Query.TrimStart('?').Split('&',StringSplitOptions.RemoveEmptyEntries)) {
            var pair=part.Split('=',2);
            q[Uri.UnescapeDataString(pair[0])]=pair.Length>1?Uri.UnescapeDataString(pair[1].Replace("+","%2B")):"";
        }
        string V(string key)=>q.TryGetValue(key,out var value)?value:"";
        if(!V("security").Equals("reality",StringComparison.OrdinalIgnoreCase)) throw new FormatException("فقط VLESS + REALITY فعلاً پشتیبانی می‌شود.");
        var host=u.Host; var port=u.IsDefaultPort||u.Port<1?443:u.Port;
        var sni=string.IsNullOrWhiteSpace(V("sni"))?host:V("sni");
        var fp=string.IsNullOrWhiteSpace(V("fp"))?"chrome":V("fp");
        var pbk=string.IsNullOrWhiteSpace(V("pbk"))?V("publickey"):V("pbk");
        var sid=string.IsNullOrWhiteSpace(V("sid"))?V("shortid"):V("sid");
        if(string.IsNullOrWhiteSpace(pbk)||!System.Text.RegularExpressions.Regex.IsMatch(sid,"^[0-9a-fA-F]{0,16}$")) throw new FormatException("کلید REALITY معتبر نیست.");
        var transport=string.IsNullOrWhiteSpace(V("type"))?"tcp":V("type").ToLowerInvariant();
        if(transport is not ("tcp" or "raw" or "ws" or "grpc" or "http" or "httpupgrade")) throw new FormatException("نوع انتقال پشتیبانی نمی‌شود.");
        var tls=new JsonObject { ["enabled"]=true,["server_name"]=sni,["utls"]=new JsonObject{["enabled"]=true,["fingerprint"]=fp},["reality"]=new JsonObject{["enabled"]=true,["public_key"]=pbk,["short_id"]=sid} };
        var outbound=new JsonObject { ["type"]="vless",["tag"]="proxy",["server"]=host,["server_port"]=port,["uuid"]=uuid,["domain_resolver"]="dns-local",["tls"]=tls };
        if(!string.IsNullOrWhiteSpace(V("flow"))) {
            if(V("flow")!="xtls-rprx-vision"||transport is not ("tcp" or "raw")) throw new FormatException("Flow با انتقال سازگار نیست.");
            outbound["flow"]=V("flow");
        }
        if(transport=="ws") {
            var ws=new JsonObject{["type"]="ws",["path"]=string.IsNullOrWhiteSpace(V("path"))?"/":V("path")};
            if(!string.IsNullOrWhiteSpace(V("host"))) ws["headers"]=new JsonObject{["Host"]=V("host").Split(',')[0].Trim()};
            outbound["transport"]=ws;
        } else if(transport=="grpc") {
            var service=string.IsNullOrWhiteSpace(V("servicename"))?V("service_name"):V("servicename");
            if(string.IsNullOrWhiteSpace(service)) service=V("service");
            if(string.IsNullOrWhiteSpace(service)) throw new FormatException("نام سرویس gRPC موجود نیست.");
            outbound["transport"]=new JsonObject{["type"]="grpc",["service_name"]=service};
        } else if(transport=="http") {
            var http=new JsonObject{["type"]="http",["path"]=string.IsNullOrWhiteSpace(V("path"))?"/":V("path")};
            var hosts=V("host").Split(',',StringSplitOptions.TrimEntries|StringSplitOptions.RemoveEmptyEntries);
            if(hosts.Length>0) http["host"]=JsonSerializer.SerializeToNode(hosts);
            outbound["transport"]=http;
        } else if(transport=="httpupgrade") {
            var upgrade=new JsonObject{["type"]="httpupgrade",["path"]=string.IsNullOrWhiteSpace(V("path"))?"/":V("path")};
            if(!string.IsNullOrWhiteSpace(V("host"))) upgrade["host"]=V("host");
            outbound["transport"]=upgrade;
        }
        var root=new JsonObject {
            ["log"]=new JsonObject{["level"]="info",["timestamp"]=true},
            ["dns"]=new JsonObject{["servers"]=new JsonArray(new JsonObject{["type"]="local",["tag"]="dns-local"}),["final"]="dns-local",["strategy"]="prefer_ipv4"},
            ["inbounds"]=new JsonArray(new JsonObject{["type"]="tun",["tag"]="tun-in",["address"]=new JsonArray("172.19.0.1/30","fdfe:dcba:9876::1/126"),["mtu"]=1400,["auto_route"]=true,["strict_route"]=true,["dns_mode"]="hijack",["stack"]="system"}),
            ["outbounds"]=new JsonArray(outbound,new JsonObject{["type"]="direct",["tag"]="direct"}),
            ["route"]=new JsonObject{["rules"]=new JsonArray(new JsonObject{["protocol"]="dns",["action"]="hijack-dns"}),["auto_detect_interface"]=true,["final"]="proxy"}
        };
        return root.ToJsonString(new JsonSerializerOptions{WriteIndented=true});
    }
}

internal sealed class SingBoxEngine : IDisposable
{
    private const string Url="https://github.com/SagerNet/sing-box/releases/download/v1.14.4/sing-box-1.14.4-windows-amd64.zip";
    private const string Hash="99185d75d314a07dbba4a130c8ad986cbeb629a07927975636b341ca61f991df";
    private Process? _process;
    private readonly string _root=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"RaahatVPN");
    public bool IsRunning=>_process is {HasExited:false};
    private async Task<string> EnsureEngine()
    {
        var dir=Path.Combine(_root,"engine"); Directory.CreateDirectory(dir);
        var exe=Path.Combine(dir,"sing-box.exe"); var stamp=Path.Combine(dir,"version.txt");
        if(File.Exists(exe)&&File.Exists(stamp)&&(await File.ReadAllTextAsync(stamp)).Trim()=="1.14.4") return exe;
        using var http=new HttpClient(new HttpClientHandler{AllowAutoRedirect=false});
        using var response=await http.GetAsync(Url); response.EnsureSuccessStatusCode();
        var bytes=await response.Content.ReadAsByteArrayAsync();
        var actual=SHA256.HashData(bytes);
        if(!CryptographicOperations.FixedTimeEquals(actual,Convert.FromHexString(Hash))) throw new CryptographicException("فایل موتور با SHA-256 رسمی مطابقت ندارد.");
        using var ms=new MemoryStream(bytes); using var zip=new ZipArchive(ms,ZipArchiveMode.Read);
        var entry=zip.Entries.FirstOrDefault(x=>x.FullName.Replace('\\','/').EndsWith("/sing-box.exe",StringComparison.OrdinalIgnoreCase)||x.FullName.Equals("sing-box.exe",StringComparison.OrdinalIgnoreCase));
        if(entry is null||entry.Length<10_000_000||entry.Length>80_000_000) throw new InvalidDataException("sing-box.exe معتبر پیدا نشد.");
        var tmp=exe+".new"; await using(var source=entry.Open()) await using(var dest=File.Create(tmp)) await source.CopyToAsync(dest);
        File.Move(tmp,exe,true); await File.WriteAllTextAsync(stamp,"1.14.4"); return exe;
    }
    public async Task Start(HybridServer server)
    {
        if(IsRunning) return;
        var exe=await EnsureEngine();
        var dir=Path.Combine(_root,"runtime"); Directory.CreateDirectory(dir);
        var config=Path.Combine(dir,"active-config.json");
        await File.WriteAllTextAsync(config,VlessConfig.Build(server.Config),new UTF8Encoding(false));
        var checkInfo=new ProcessStartInfo(exe){UseShellExecute=false,CreateNoWindow=true,RedirectStandardError=true};
        checkInfo.ArgumentList.Add("check"); checkInfo.ArgumentList.Add("-c"); checkInfo.ArgumentList.Add(config);
        using(var check=Process.Start(checkInfo)??throw new InvalidOperationException("موتور VPN اجرا نشد.")) {
            var error=await check.StandardError.ReadToEndAsync(); await check.WaitForExitAsync();
            if(check.ExitCode!=0) throw new InvalidOperationException("پیکربندی رد شد: "+error[..Math.Min(220,error.Length)]);
        }
        var info=new ProcessStartInfo(exe){UseShellExecute=false,CreateNoWindow=true,RedirectStandardError=true,RedirectStandardOutput=true};
        info.ArgumentList.Add("run"); info.ArgumentList.Add("-c"); info.ArgumentList.Add(config);
        _process=Process.Start(info)??throw new InvalidOperationException("موتور VPN آغاز نشد.");
        _process.BeginOutputReadLine(); _process.BeginErrorReadLine();
        await Task.Delay(1800);
        if(_process.HasExited) throw new InvalidOperationException($"موتور VPN متوقف شد (کد {_process.ExitCode}).");
        if(!await VerifyTraffic()) { await Stop(); throw new InvalidOperationException("عبور واقعی اینترنت از تونل تأیید نشد."); }
    }
    private static async Task<bool> VerifyTraffic()
    {
        using var handler=new HttpClientHandler{UseProxy=false,AllowAutoRedirect=false};
        using var client=new HttpClient(handler){Timeout=TimeSpan.FromSeconds(4)};
        foreach(var url in new[]{"https://api.github.com/zen","https://www.cloudflare.com/cdn-cgi/trace","https://www.microsoft.com/"}) {
            try { using var req=new HttpRequestMessage(HttpMethod.Get,url); req.Headers.UserAgent.ParseAdd("RaahatVPN-Windows/1.0"); using var res=await client.SendAsync(req); if((int)res.StatusCode is >=200 and <300) return true; }
            catch(HttpRequestException){} catch(TaskCanceledException){}
        }
        return false;
    }
    public Task Stop()
    {
        var p=_process; _process=null;
        try { if(p is {HasExited:false}) p.Kill(true); p?.Dispose(); }
        catch(InvalidOperationException){} catch(Win32Exception){}
        return Task.CompletedTask;
    }
    public void Dispose()=>Stop().GetAwaiter().GetResult();
}

public partial class MainWindow : Window
{
    private readonly TokenVault _vault=new();
    private readonly SingBoxEngine _engine=new();
    private readonly bool _connectOnLaunch;
    private HybridApi? _api;
    private List<HybridServer> _servers=new();
    private bool _busy;
    public MainWindow()
    {
        InitializeComponent();
        _connectOnLaunch=Environment.GetCommandLineArgs().Any(x=>x.Equals("--connect",StringComparison.OrdinalIgnoreCase));
        Loaded+=OnLoaded; Closed+=(_,_)=>{_engine.Dispose();_api?.Dispose();};
    }
    private async void OnLoaded(object sender,RoutedEventArgs e)
    {
        TokenBox.Password=_vault.Read()??"";
        if(_connectOnLaunch){await Sync();if(_servers.Count>0)await Connect();}
    }
    private async void SyncButton_Click(object sender,RoutedEventArgs e)=>await Sync();
    private async Task Sync()
    {
        if(_busy)return; SetBusy(true);
        try{
            var endpoint=Environment.GetEnvironmentVariable("RAAHAT_API_BASE_URL");
            if(string.IsNullOrWhiteSpace(endpoint))throw new InvalidOperationException("نشانی HTTPS سرویس مرکزی هنوز تنظیم نشده است.");
            var token=string.IsNullOrWhiteSpace(TokenBox.Password)?_vault.Read():TokenBox.Password.Trim();
            if(string.IsNullOrWhiteSpace(token))throw new InvalidOperationException("کد دسترسی حساب را وارد کنید.");
            _api?.Dispose();_api=new HybridApi(endpoint);
            var catalog=await _api.Fetch(token);_vault.Save(token);
            if(catalog.Plan!="premium"){
                _servers.Clear();ServerList.ItemsSource=null;PlanText.Text="حساب رایگان است؛ اتصال رایگان Windows هنوز تکمیل نشده.";StatusText.Text="حساب رایگان";
            }else{
                _servers=catalog.PremiumServers;ServerList.ItemsSource=_servers;
                PlanText.Text=$"اشتراک ویژه تأیید شد · {_servers.Count} سرور";
                if(_servers.Count>0){ServerList.SelectedIndex=0;StatusText.Text="آماده اتصال";}
                else StatusText.Text="سروری موجود نیست";
            }
        }catch(Exception ex){StatusText.Text="خطا";DetailText.Text=ex.Message;}
        finally{SetBusy(false);}
    }
    private void ServerList_SelectionChanged(object sender,SelectionChangedEventArgs e)
    {
        if(ServerList.SelectedItem is HybridServer s) DetailText.Text=$"{s.Name} · {s.Country} · {s.Host}:{s.Port}";
    }
    private async void ConnectButton_Click(object sender,RoutedEventArgs e)=>await Connect();
    private async Task Connect()
    {
        if(_engine.IsRunning){await _engine.Stop();StatusText.Text="قطع شد";ConnectButton.Content="اتصال";return;}
        if(!IsAdmin()){
            try{
                var path=Environment.ProcessPath??throw new InvalidOperationException("مسیر برنامه مشخص نیست.");
                Process.Start(new ProcessStartInfo(path){UseShellExecute=true,Verb="runas",Arguments="--connect"});Close();
            }catch(Win32Exception ex)when(ex.NativeErrorCode==1223){DetailText.Text="برای اتصال VPN، مجوز مدیر لازم است.";}
            catch(Exception ex){DetailText.Text=ex.Message;}
            return;
        }
        if(_servers.Count==0){await Sync();if(_servers.Count==0)return;}
        var selected=ServerList.SelectedItem as HybridServer;
        selected??=await BestServer(_servers);
        SetBusy(true);
        try{
            await _engine.Start(selected);StatusText.Text="متصل";DetailText.Text=$"عبور اینترنت از طریق {selected.Name} تأیید شد.";ConnectButton.Content="قطع اتصال";
        }catch(Exception ex){StatusText.Text="اتصال ناموفق";DetailText.Text=ex.Message;}
        finally{SetBusy(false);}
    }
    private static async Task<HybridServer> BestServer(IReadOnlyList<HybridServer> servers)
    {
        var all=await Task.WhenAll(servers.Select(async s=>{
            var sw=Stopwatch.StartNew();
            try{using var tcp=new TcpClient();using var cts=new CancellationTokenSource(TimeSpan.FromSeconds(2));await tcp.ConnectAsync(s.Host,s.Port,cts.Token);sw.Stop();return(Server:s,Ms:sw.Elapsed.TotalMilliseconds);}
            catch{return(Server:s,Ms:double.PositiveInfinity);}
        }));
        var best=all.OrderBy(x=>x.Ms).FirstOrDefault();
        if(best.Server is null||double.IsPositiveInfinity(best.Ms))throw new InvalidOperationException("هیچ سرور ویژه‌ای در آزمون TCP پاسخ نداد.");
        return best.Server;
    }
    private void SetBusy(bool busy){_busy=busy;SyncButton.IsEnabled=!busy;ConnectButton.IsEnabled=!busy;ServerList.IsEnabled=!busy;}
    private static bool IsAdmin(){using var id=WindowsIdentity.GetCurrent();return new WindowsPrincipal(id).IsInRole(WindowsBuiltInRole.Administrator);}
}
