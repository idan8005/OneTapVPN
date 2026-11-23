using System.Diagnostics;
using System.Net;
using System.Text;
using System.Text.Json;
using RaahatVPN;

static void Require(bool condition, string message)
{
    if (!condition) throw new InvalidOperationException(message);
}

static void MustReject(string link, string label)
{
    try
    {
        _ = VlessRealityProfile.Parse(link);
        throw new InvalidOperationException($"Parser accepted invalid test input: {label}");
    }
    catch (FormatException)
    {
        // Expected.
    }
}

static void MustRejectCandidateList(string input, string label)
{
    try
    {
        _ = VlessCandidateList.Parse(input);
        throw new InvalidOperationException($"Candidate list accepted invalid test input: {label}");
    }
    catch (FormatException)
    {
        // Expected.
    }
}


static async Task ValidatePowerShellSyntaxAsync(string script, string label)
{
    var scriptPath = Path.Combine(
        Path.GetTempPath(),
        "raahat-" + Guid.NewGuid().ToString("N") + ".ps1");
    try
    {
        await File.WriteAllTextAsync(scriptPath, script, new UTF8Encoding(false));
        var powershellPath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.System),
            "WindowsPowerShell", "v1.0", "powershell.exe");
        using var parser = new Process
        {
            StartInfo = new ProcessStartInfo
            {
                FileName = powershellPath,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            }
        };
        var escapedScriptPath = scriptPath.Replace("'", "''", StringComparison.Ordinal);
        parser.StartInfo.ArgumentList.Add("-NoLogo");
        parser.StartInfo.ArgumentList.Add("-NoProfile");
        parser.StartInfo.ArgumentList.Add("-NonInteractive");
        parser.StartInfo.ArgumentList.Add("-Command");
        parser.StartInfo.ArgumentList.Add(
            "$tokens=$null;$errors=$null;" +
            "[System.Management.Automation.Language.Parser]::ParseFile('" + escapedScriptPath +
            "',[ref]$tokens,[ref]$errors)|Out-Null;" +
            "if($errors.Count -gt 0){$errors|ForEach-Object{Write-Error $_};exit 1}");
        Require(parser.Start(), "Could not start PowerShell parser for " + label + ".");
        var stdout = parser.StandardOutput.ReadToEndAsync();
        var stderr = parser.StandardError.ReadToEndAsync();
        if (!parser.WaitForExit(15000))
        {
            try { parser.Kill(entireProcessTree: true); } catch { }
            throw new InvalidOperationException("PowerShell syntax validation timed out for " + label + ".");
        }
        await Task.WhenAll(stdout, stderr);
        Require(parser.ExitCode == 0, "PowerShell syntax is invalid for " + label + ". " + stderr.Result);
    }
    finally
    {
        try { File.Delete(scriptPath); } catch { }
    }
}

if (args.Length != 1 || !File.Exists(args[0]))
{
    Console.Error.WriteLine("Usage: RaahatVPN.Verification <path-to-sing-box.exe>");
    return 2;
}

const string validLink =
    "vless://11111111-1111-4111-8111-111111111111@example.com:443" +
    "?security=reality&type=tcp" +
    "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "&sid=0123456789abcdef&sni=example.com&fp=chrome#Test%20Relay";

var candidateList = VlessCandidateList.Parse(
    $"# Raahat backup list{Environment.NewLine}{validLink}{Environment.NewLine}{validLink}{Environment.NewLine}" +
    validLink.Replace("example.com", "backup.example.com", StringComparison.Ordinal));
Require(candidateList.Count == 2, "Backup link list did not remove duplicate entries.");
Require(candidateList[0] == validLink, "Backup link order was not preserved.");
Require(candidateList[1].Contains("backup.example.com", StringComparison.Ordinal),
    "Second backup link was not preserved.");
MustRejectCandidateList("https://example.com", "non-VLESS candidate");
MustRejectCandidateList(
    string.Join(Environment.NewLine, Enumerable.Range(0, VlessCandidateList.MaximumCandidates + 1)
        .Select(index => validLink.Replace("example.com", $"relay-{index}.example.com", StringComparison.Ordinal))),
    "more than five candidate links");

var mixedPublicFeed = string.Join(Environment.NewLine, new[]
{
    "# Public feed header",
    validLink,
    validLink,
    validLink.Replace("type=tcp", "type=grpc", StringComparison.Ordinal),
    validLink.Replace("security=reality", "security=tls", StringComparison.Ordinal),
    validLink.Replace(
        "pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "pbk=invalid",
        StringComparison.Ordinal),
    "vless://not-a-valid-profile"
});
var filteredPublicFeed = PublicVlessFeedClient.FilterSupportedCandidates(mixedPublicFeed);
Require(filteredPublicFeed.Count == 1, "Public feed filter accepted unsupported or duplicate profiles.");
Require(filteredPublicFeed[0] == validLink, "Public feed filter changed a valid share link.");

var manyPublicCandidates = string.Join(Environment.NewLine,
    Enumerable.Range(0, 8).Select(index =>
        validLink.Replace("example.com", "relay-" + index + ".example.com", StringComparison.Ordinal)));
var limitedPublicFeed = PublicVlessFeedClient.FilterSupportedCandidates(manyPublicCandidates);
Require(limitedPublicFeed.Count == VlessCandidateList.MaximumCandidates,
    "Public feed filter did not enforce the five-candidate connection limit.");

var sameHostAlternatePort = validLink.Replace(":443", ":444", StringComparison.Ordinal);
var uniqueHostFallback = validLink.Replace(
    "example.com",
    "second.example.com",
    StringComparison.Ordinal);
var hostDiversityCandidates = PublicVlessFeedClient.FilterSupportedCandidates(
    string.Join(Environment.NewLine, new[] { validLink, sameHostAlternatePort, uniqueHostFallback }),
    3);
Require(hostDiversityCandidates.Count == 3, "Public feed did not preserve fallback candidates.");
Require(hostDiversityCandidates[0] == validLink, "Public feed did not preserve the first ranked host.");
Require(hostDiversityCandidates[1] == uniqueHostFallback,
    "Public feed did not prefer a different host over a duplicate host.");
Require(hostDiversityCandidates[2] == sameHostAlternatePort,
    "Public feed did not retain an alternate port as a final fallback.");

var profile = VlessRealityProfile.Parse(validLink);
Require(profile.Server == "example.com", "Server hostname parsing failed.");
Require(profile.ServerPort == 443, "Server port parsing failed.");
Require(profile.Name == "Test Relay", "URI fragment decoding failed.");

MustReject(
    validLink.Replace("vless://", "https://", StringComparison.Ordinal),
    "wrong protocol");
MustReject(
    validLink.Replace("security=reality", "security=none", StringComparison.Ordinal),
    "non-Reality security");
MustReject(
    validLink.Replace("type=tcp", "type=udp", StringComparison.Ordinal),
    "unsupported UDP transport");
MustReject(
    validLink.Replace("#Test%20Relay", "&security=reality#Test%20Relay", StringComparison.Ordinal),
    "duplicate query key");
MustReject(
    validLink.Replace("pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "pbk=bad", StringComparison.Ordinal),
    "malformed Reality public key");

var json = profile.ToSingBoxConfig();
using (var document = JsonDocument.Parse(json))
{
    var root = document.RootElement;
    Require(root.GetProperty("inbounds")[0].GetProperty("type").GetString() == "tun",
        "TUN inbound was not generated.");
    Require(root.GetProperty("inbounds")[0].GetProperty("auto_route").GetBoolean(),
        "Full Tunnel auto_route was not enabled.");
    Require(root.GetProperty("inbounds")[0].GetProperty("strict_route").GetBoolean(),
        "Strict route was not enabled.");
    Require(root.GetProperty("dns").GetProperty("servers")[0].GetProperty("type").GetString() == "https",
        "Encrypted DNS over HTTPS was not configured.");
    Require(root.GetProperty("route").GetProperty("final").GetString() == "proxy",
        "Full Tunnel final route was not set to the proxy.");
    Require(root.GetProperty("inbounds")[0].GetProperty("interface_name").GetString() == WindowsKillSwitch.TunnelInterfaceAlias,
        "The TUN interface name does not match the Windows firewall allow rule.");
}


var pinnedConfig = profile.ToSingBoxConfig("203.0.113.10");
using (var pinnedDocument = JsonDocument.Parse(pinnedConfig))
{
    Require(pinnedDocument.RootElement.GetProperty("outbounds")[0].GetProperty("server").GetString() == "203.0.113.10",
        "Resolved server IP was not pinned into the sing-box outbound.");
    Require(pinnedDocument.RootElement.GetProperty("outbounds")[0].GetProperty("tls").GetProperty("server_name").GetString() == "example.com",
        "Original TLS server name was not preserved after IP pinning.");
}

var recoveryScript = WindowsKillSwitch.BuildRecoveryScript();
Require(recoveryScript.Contains("Set-NetFirewallProfile", StringComparison.Ordinal) &&
        recoveryScript.Contains("RestoreRequested", StringComparison.Ordinal) &&
        recoveryScript.Contains("Remove-NetFirewallRule", StringComparison.Ordinal) &&
        recoveryScript.Contains("AllowRules", StringComparison.Ordinal) &&
        recoveryScript.Contains("LOCKED_RECOVERY_REQUIRED", StringComparison.Ordinal) &&
        recoveryScript.Contains("Restore only after", StringComparison.Ordinal) &&
        recoveryScript.Contains("OwnerStartTimeUtcTicks", StringComparison.Ordinal) &&
        recoveryScript.Contains("Get-Process -Id", StringComparison.Ordinal) &&
        recoveryScript.Contains(".StartTime.ToUniversalTime().Ticks", StringComparison.Ordinal) &&
        recoveryScript.Contains("actualStartTicks -eq $expectedStartTicks", StringComparison.Ordinal) &&
        recoveryScript.Contains("Backward compatibility for a state file created by an older app build", StringComparison.Ordinal) &&
        recoveryScript.Contains("recovery.lock", StringComparison.Ordinal) &&
        recoveryScript.Contains("[System.IO.FileShare]::None", StringComparison.Ordinal),
    "Kill Switch recovery script does not contain the required rollback and exclusive-lock operations.");

var testEnginePath = Path.Combine(
    Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
    "RaahatVPN", "RaahatVPN.exe");
var testEndpoint = IPAddress.Parse("1.1.1.1");
var firewallLockScript = WindowsKillSwitch.BuildFirewallLockScript(
    testEnginePath, testEndpoint, 443);
var endpointAllowScript = WindowsKillSwitch.BuildEndpointAllowScript(
    testEnginePath, testEndpoint, 443);
Require(firewallLockScript.Contains("DefaultOutboundAction Block", StringComparison.Ordinal) &&
        firewallLockScript.Contains("-Enabled True", StringComparison.Ordinal) &&
        firewallLockScript.Contains("Disable-NetFirewallRule", StringComparison.Ordinal) &&
        firewallLockScript.Contains("RaahatVPN", StringComparison.Ordinal),
    "Kill Switch firewall lock script is missing critical block/allow rules.");
Require(endpointAllowScript.Contains("RemoteAddress '1.1.1.1'", StringComparison.Ordinal) &&
        endpointAllowScript.Contains("RemotePort 443", StringComparison.Ordinal),
    "Kill Switch endpoint exception is not restricted to the selected server IP and port.");

await ValidatePowerShellSyntaxAsync(firewallLockScript, "firewall lock script");
await ValidatePowerShellSyntaxAsync(endpointAllowScript, "engine endpoint allow script");
await ValidatePowerShellSyntaxAsync(recoveryScript, "firewall recovery script");


var configPath = Path.Combine(Path.GetTempPath(), $"raahat-config-check-{Guid.NewGuid():N}.json");
try
{
    await File.WriteAllTextAsync(configPath, json);
    using var process = new Process
    {
        StartInfo = new ProcessStartInfo
        {
            FileName = Path.GetFullPath(args[0]),
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        }
    };
    process.StartInfo.ArgumentList.Add("check");
    process.StartInfo.ArgumentList.Add("-c");
    process.StartInfo.ArgumentList.Add(configPath);

    Require(process.Start(), "Could not start sing-box config validation.");
    var stdout = process.StandardOutput.ReadToEndAsync();
    var stderr = process.StandardError.ReadToEndAsync();
    if (!process.WaitForExit(20000))
    {
        try { process.Kill(entireProcessTree: true); } catch { }
        throw new InvalidOperationException("sing-box config validation timed out.");
    }

    await Task.WhenAll(stdout, stderr);
    Require(process.ExitCode == 0,
        "The generated Windows TUN/DoH/VLESS Reality config was rejected by the pinned sing-box engine.");
}
finally
{
    try { File.Delete(configPath); } catch { }
}

Console.WriteLine("Windows VLESS Reality parsing tests: PASS");
Console.WriteLine("Windows backup candidate-list parsing and limits: PASS");
Console.WriteLine("Public feed filtering, compatibility checks and candidate limit: PASS");
Console.WriteLine("Generated Full Tunnel + strict routing + DoH config: PASS");
Console.WriteLine("Pinned VPN server endpoint and preserved SNI: PASS");
Console.WriteLine("Kill Switch firewall lock, endpoint rules, process-identity checks and crash-recovery scripts: PASS");
Console.WriteLine("Pinned sing-box configuration validation: PASS");
return 0;
