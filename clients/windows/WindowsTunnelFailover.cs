using System;
using System.Collections.Generic;
using System.Net;
using System.Threading.Tasks;

namespace RaahatVPN;

public static class WindowsTunnelFailover
{
    /// <summary>
    /// Resolves all candidate endpoints before the first firewall lock, then
    /// tries them one by one while keeping the kill switch armed between retries.
    /// </summary>
    public static async Task<TunnelConnectResult> ConnectBestAsync(
        this WindowsTunnelController tunnel,
        string serverLinks)
    {
        var input = VlessCandidateList.Parse(serverLinks);
        var candidates = new List<(string Link, IPAddress Endpoint)>(input.Count);
        string lastFailure = "بررسی مستقل خروجی اینترنت موفق نشد.";

        // Resolve all candidates before locking the physical adapter.
        foreach (var link in input)
        {
            try
            {
                var endpoint = await tunnel.ResolveServerAddressAsync(link);
                candidates.Add((link, endpoint));
            }
            catch (Exception error)
            {
                lastFailure = error.Message;
            }
        }

        if (candidates.Count == 0)
            throw new InvalidOperationException(
                "هیچ‌کدام از لینک‌ها به IP عمومی IPv4 معتبر تبدیل نشدند. " + lastFailure);

        for (var index = 0; index < candidates.Count; index++)
        {
            try
            {
                var candidate = candidates[index];
                var result = await tunnel.ConnectResolvedAsync(candidate.Link, candidate.Endpoint);
                if (result.EgressVerified)
                {
                    return result with
                    {
                        Message = $"سرور {index + 1} از {candidates.Count} انتخاب شد. {result.Message}"
                    };
                }

                lastFailure = result.Message;
            }
            catch (Exception error)
            {
                // Never log or echo the VLESS URI.
                lastFailure = error.Message;
            }

            await tunnel.StopAsync(preserveKillSwitch: true);
            if (tunnel.IsRunning)
                throw new InvalidOperationException(
                    "موتور سرور قبلی به‌طور کامل متوقف نشد؛ برای جلوگیری از دو تونل هم‌زمان، تلاش بعدی لغو شد.");
        }

        // No candidate passed the egress test: restore the user's previous firewall settings.
        await tunnel.StopAsync();
        throw new InvalidOperationException(
            $"اتصال تأییدشده‌ای از هیچ‌یک از {candidates.Count} سرور به دست نیامد. آخرین وضعیت: {lastFailure}");
    }
}
