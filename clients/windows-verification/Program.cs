using System.Diagnostics;
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
}

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
Console.WriteLine("Generated Full Tunnel + strict routing + DoH config: PASS");
Console.WriteLine("Pinned sing-box configuration validation: PASS");
return 0;
