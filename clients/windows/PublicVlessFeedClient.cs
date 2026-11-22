using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace RaahatVPN;

/// <summary>
/// Retrieves a public subscription and keeps only profiles that the current
/// Windows sing-box client can parse: VLESS + REALITY over TCP.
/// Upstream reachability is never treated as proof that a relay works here.
/// </summary>
public sealed class PublicVlessFeedClient : IDisposable
{
    public const int MaximumFeedBytes = 512 * 1024;

    private static readonly Uri[] FeedMirrors =
    {
        new("https://cdn.jsdelivr.net/gh/FLAT447/v2ray-lists@main/WHITE_FULL.txt"),
        new("https://raw.githack.com/FLAT447/v2ray-lists/main/WHITE_FULL.txt"),
        new("https://raw.githubusercontent.com/FLAT447/v2ray-lists/main/WHITE_FULL.txt")
    };

    private readonly HttpClient _httpClient;

    public PublicVlessFeedClient()
    {
        // Keep the Windows system proxy enabled: some networks require it to
        // reach public feeds. TLS certificate validation remains enabled.
        var handler = new HttpClientHandler
        {
            AllowAutoRedirect = false,
            AutomaticDecompression =
                DecompressionMethods.GZip |
                DecompressionMethods.Deflate |
                DecompressionMethods.Brotli
        };

        _httpClient = new HttpClient(handler)
        {
            Timeout = TimeSpan.FromSeconds(12)
        };
        _httpClient.DefaultRequestHeaders.UserAgent.ParseAdd("RaahatVPN/0.2.2");
    }

    public async Task<IReadOnlyList<string>> FetchCandidatesAsync(
        CancellationToken cancellationToken = default)
    {
        var lastError = "منبع عمومی پاسخ قابل استفاده نداد.";

        foreach (var feedUri in FeedMirrors)
        {
            try
            {
                using var response = await _httpClient.GetAsync(
                    feedUri,
                    HttpCompletionOption.ResponseHeadersRead,
                    cancellationToken);

                if (!response.IsSuccessStatusCode)
                {
                    lastError = "دریافت فهرست از منبع عمومی با کد HTTP " +
                        ((int)response.StatusCode).ToString() + " ناموفق بود.";
                    continue;
                }

                var finalHost = response.RequestMessage?.RequestUri?.IdnHost;
                if (string.IsNullOrWhiteSpace(finalHost) || !IsTrustedMirrorHost(finalHost))
                {
                    lastError = "مسیر تغییرمسیر منبع عمومی مجاز نبود.";
                    continue;
                }

                if (response.Content.Headers.ContentLength is long length &&
                    length > MaximumFeedBytes)
                {
                    lastError = "اندازه فهرست دریافتی بیش از حد مجاز است.";
                    continue;
                }

                var content = await ReadBoundedTextAsync(response.Content, cancellationToken);
                var candidates = FilterSupportedCandidates(content);
                if (candidates.Count > 0)
                    return candidates;

                lastError = "فهرست دریافت شد، اما لینک سازگار VLESS + REALITY + TCP در آن پیدا نشد.";
            }
            catch (TaskCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                lastError = "مهلت دریافت فهرست از منبع عمومی تمام شد.";
            }
            catch (HttpRequestException)
            {
                lastError = "ارتباط HTTPS با منبع عمومی برقرار نشد.";
            }
            catch (InvalidDataException)
            {
                lastError = "اندازه یا قالب فهرست دریافتی معتبر نبود.";
            }
            catch (IOException)
            {
                lastError = "خواندن فهرست دریافتی کامل نشد.";
            }
        }

        throw new InvalidOperationException(
            "دریافت سرورهای رایگان ناموفق بود. اتصال اینترنت یا دسترسی به منابع عمومی را بررسی کنید و دوباره تلاش کنید. " +
            lastError);
    }

    public static IReadOnlyList<string> FilterSupportedCandidates(
        string? feedContent,
        int maximumCandidates = VlessCandidateList.MaximumCandidates)
    {
        if (maximumCandidates is < 1 or > VlessCandidateList.MaximumCandidates)
            throw new ArgumentOutOfRangeException(nameof(maximumCandidates));

        if (string.IsNullOrWhiteSpace(feedContent))
            return Array.Empty<string>();

        if (Encoding.UTF8.GetByteCount(feedContent) > MaximumFeedBytes)
            throw new InvalidDataException("Feed exceeds the maximum supported size.");

        var accepted = new List<string>(maximumCandidates);
        var seen = new HashSet<string>(StringComparer.Ordinal);

        foreach (var rawLine in feedContent.Split(
                     new[] { '\r', '\n' },
                     StringSplitOptions.RemoveEmptyEntries))
        {
            var candidate = rawLine.Trim();
            if (candidate.Length == 0 ||
                candidate.StartsWith("#", StringComparison.Ordinal) ||
                candidate.Length > VlessCandidateList.MaximumLinkLength ||
                !candidate.StartsWith("vless://", StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            try
            {
                // This is the same strict parser used immediately before
                // sing-box configuration generation and engine startup.
                _ = VlessRealityProfile.Parse(candidate);
            }
            catch (FormatException)
            {
                continue;
            }

            if (!seen.Add(candidate))
                continue;

            accepted.Add(candidate);
            if (accepted.Count == maximumCandidates)
                break;
        }

        return accepted;
    }

    private static bool IsTrustedMirrorHost(string host) =>
        host.Equals("cdn.jsdelivr.net", StringComparison.OrdinalIgnoreCase) ||
        host.Equals("raw.githack.com", StringComparison.OrdinalIgnoreCase) ||
        host.Equals("raw.githubusercontent.com", StringComparison.OrdinalIgnoreCase);

    private static async Task<string> ReadBoundedTextAsync(
        HttpContent content,
        CancellationToken cancellationToken)
    {
        await using var stream = await content.ReadAsStreamAsync(cancellationToken);
        using var output = new MemoryStream();
        var buffer = new byte[8192];

        while (true)
        {
            var read = await stream.ReadAsync(buffer.AsMemory(0, buffer.Length), cancellationToken);
            if (read == 0)
                break;

            if (output.Length + read > MaximumFeedBytes)
                throw new InvalidDataException("Feed exceeds the maximum supported size.");

            output.Write(buffer, 0, read);
        }

        return Encoding.UTF8.GetString(output.ToArray());
    }

    public void Dispose() => _httpClient.Dispose();
}
