using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace RaahatVPN;

public sealed record VlessRealityProfile(
    string Name,
    string Server,
    int ServerPort,
    string Uuid,
    string ServerName,
    string PublicKey,
    string ShortId,
    string Fingerprint,
    string Flow)
{
    public static VlessRealityProfile Parse(string link)
    {
        if (string.IsNullOrWhiteSpace(link) || link.Length > 8192)
            throw new FormatException("لینک سرور خالی یا بیش از حد طولانی است.");

        if (!Uri.TryCreate(link.Trim(), UriKind.Absolute, out var uri))
            throw new FormatException("ساختار لینک سرور معتبر نیست.");

        if (!uri.Scheme.Equals("vless", StringComparison.OrdinalIgnoreCase))
            throw new FormatException("فعلاً فقط لینک VLESS + REALITY پشتیبانی می‌شود.");

        if (string.IsNullOrWhiteSpace(uri.Host))
            throw new FormatException("آدرس سرور در لینک وجود ندارد.");

        if (uri.Port is < 1 or > 65535)
            throw new FormatException("پورت سرور معتبر نیست.");

        if (!Guid.TryParse(uri.UserInfo, out var userId))
            throw new FormatException("شناسه کاربر VLESS معتبر نیست.");

        var query = ParseQuery(uri.Query);
        if (!Get(query, "security").Equals("reality", StringComparison.OrdinalIgnoreCase))
            throw new FormatException("این نسخه فقط لینک‌های VLESS + REALITY را می‌پذیرد.");

        var network = Get(query, "type");
        if (string.IsNullOrEmpty(network))
            network = Get(query, "network");
        if (string.IsNullOrEmpty(network))
            network = "tcp";
        if (!network.Equals("tcp", StringComparison.OrdinalIgnoreCase))
            throw new FormatException("در این نسخه فقط انتقال TCP برای VLESS + REALITY پشتیبانی می‌شود.");

        var publicKey = First(query, "pbk", "publicKey");
        if (!IsValidRealityPublicKey(publicKey))
            throw new FormatException("کلید عمومی REALITY موجود نیست یا قالب آن معتبر نیست.");

        var shortId = First(query, "sid", "shortId");
        if (shortId.Length > 16 || shortId.Length % 2 != 0 ||
            shortId.Any(c => !Uri.IsHexDigit(c)))
            throw new FormatException("شناسه کوتاه REALITY معتبر نیست.");

        var serverName = First(query, "sni", "serverName");
        if (string.IsNullOrWhiteSpace(serverName))
            serverName = uri.IdnHost;
        if (!IsValidServerName(serverName))
            throw new FormatException("نام میزبان TLS معتبر نیست.");

        var fingerprint = Get(query, "fp");
        if (string.IsNullOrWhiteSpace(fingerprint))
            fingerprint = "chrome";
        if (fingerprint.Length > 32 ||
            fingerprint.Any(c => !char.IsAsciiLetterOrDigit(c) && c is not '_' and not '-'))
            throw new FormatException("Fingerprint لینک معتبر نیست.");

        var flow = Get(query, "flow");
        if (!string.IsNullOrEmpty(flow) && flow != "xtls-rprx-vision")
            throw new FormatException("Flow این سرور پشتیبانی نمی‌شود.");

        var name = string.IsNullOrWhiteSpace(uri.Fragment)
            ? $"{uri.IdnHost}:{uri.Port}"
            : Uri.UnescapeDataString(uri.Fragment.TrimStart('#'));

        return new VlessRealityProfile(
            name.Length > 80 ? name[..80] : name,
            uri.IdnHost,
            uri.Port,
            userId.ToString(),
            serverName,
            publicKey,
            shortId,
            fingerprint,
            flow);
    }

    public string ToSingBoxConfig()
    {
        var vless = new JsonObject
        {
            ["type"] = "vless",
            ["tag"] = "proxy",
            ["server"] = Server,
            ["server_port"] = ServerPort,
            ["uuid"] = Uuid,
            ["tls"] = new JsonObject
            {
                ["enabled"] = true,
                ["server_name"] = ServerName,
                ["utls"] = new JsonObject
                {
                    ["enabled"] = true,
                    ["fingerprint"] = Fingerprint
                },
                ["reality"] = new JsonObject
                {
                    ["enabled"] = true,
                    ["public_key"] = PublicKey,
                    ["short_id"] = ShortId
                }
            }
        };

        if (!string.IsNullOrEmpty(Flow))
            vless["flow"] = Flow;

        var config = new JsonObject
        {
            ["log"] = new JsonObject
            {
                ["level"] = "warn",
                ["timestamp"] = true
            },
            ["dns"] = new JsonObject
            {
                ["servers"] = new JsonArray
                {
                    new JsonObject
                    {
                        ["type"] = "https",
                        ["tag"] = "raahat-doh",
                        ["server"] = "1.1.1.1",
                        ["server_port"] = 443,
                        ["path"] = "/dns-query",
                        ["tls"] = new JsonObject
                        {
                            ["enabled"] = true,
                            ["server_name"] = "cloudflare-dns.com"
                        },
                        ["detour"] = "proxy"
                    }
                },
                ["final"] = "raahat-doh",
                ["strategy"] = "prefer_ipv4"
            },
            ["inbounds"] = new JsonArray
            {
                new JsonObject
                {
                    ["type"] = "tun",
                    ["tag"] = "raahat-tun",
                    ["address"] = new JsonArray("172.19.0.1/30", "fdfe:dcba:9876::1/126"),
                    ["mtu"] = 1400,
                    ["auto_route"] = true,
                    ["strict_route"] = true,
                    ["dns_mode"] = "hijack",
                    ["dns_address"] = new JsonArray("172.19.0.2", "fdfe:dcba:9876::2"),
                    ["route_exclude_address"] = new JsonArray(
                        "10.0.0.0/8",
                        "100.64.0.0/10",
                        "127.0.0.0/8",
                        "169.254.0.0/16",
                        "172.16.0.0/12",
                        "192.168.0.0/16",
                        "::1/128",
                        "fc00::/7",
                        "fe80::/10")
                }
            },
            ["outbounds"] = new JsonArray
            {
                vless,
                new JsonObject { ["type"] = "direct", ["tag"] = "direct" },
                new JsonObject { ["type"] = "block", ["tag"] = "block" }
            },
            ["route"] = new JsonObject
            {
                ["auto_detect_interface"] = true,
                ["final"] = "proxy"
            }
        };

        return config.ToJsonString(new JsonSerializerOptions { WriteIndented = true });
    }

    private static Dictionary<string, string> ParseQuery(string queryString)
    {
        var result = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var item in queryString.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var parts = item.Split('=', 2);
            var key = Uri.UnescapeDataString(parts[0]);
            var value = parts.Length == 2 ? Uri.UnescapeDataString(parts[1]) : string.Empty;
            if (string.IsNullOrWhiteSpace(key) || result.ContainsKey(key))
                throw new FormatException("پارامتر تکراری یا نامعتبر در لینک سرور وجود دارد.");
            result.Add(key, value);
        }
        return result;
    }

    private static string Get(IReadOnlyDictionary<string, string> query, string key) =>
        query.TryGetValue(key, out var value) ? value : string.Empty;

    private static string First(IReadOnlyDictionary<string, string> query, string primary, string alternate)
    {
        var value = Get(query, primary);
        return string.IsNullOrEmpty(value) ? Get(query, alternate) : value;
    }

    private static bool IsValidRealityPublicKey(string value)
    {
        if (value.Length is < 40 or > 64 ||
            value.Any(c => !char.IsAsciiLetterOrDigit(c) && c is not '-' and not '_'))
            return false;

        try
        {
            var base64 = value.Replace('-', '+').Replace('_', '/');
            base64 = base64.PadRight((base64.Length + 3) / 4 * 4, '=');
            return Convert.FromBase64String(base64).Length == 32;
        }
        catch (FormatException)
        {
            return false;
        }
    }

    private static bool IsValidServerName(string value)
    {
        if (value.Length > 253 || value.Any(char.IsWhiteSpace))
            return false;

        var hostType = Uri.CheckHostName(value);
        return hostType is UriHostNameType.Dns or UriHostNameType.IPv4 or UriHostNameType.IPv6;
    }
}
