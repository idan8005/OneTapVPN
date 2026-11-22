using System;
using System.Threading.Tasks;

namespace RaahatVPN;

public static class WindowsTunnelFailover
{
    /// <summary>
    /// Tries up to five user-supplied VLESS links in order. A candidate is kept
    /// only when the HTTPS IPv4 egress check confirms that the public IP changed.
    /// Unverified candidates are stopped before the next candidate is attempted.
    /// </summary>
    public static async Task<TunnelConnectResult> ConnectBestAsync(
        this WindowsTunnelController tunnel,
        string serverLinks)
    {
        var candidates = VlessCandidateList.Parse(serverLinks);
        string lastFailure = "بررسی مستقل خروجی اینترنت موفق نشد.";

        for (var index = 0; index < candidates.Count; index++)
        {
            try
            {
                var result = await tunnel.ConnectAsync(candidates[index]);
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

            await tunnel.StopAsync();
            if (tunnel.IsRunning)
                throw new InvalidOperationException(
                    "موتور سرور قبلی به‌طور کامل متوقف نشد؛ برای جلوگیری از دو تونل هم‌زمان، تلاش بعدی لغو شد.");
        }

        throw new InvalidOperationException(
            $"اتصال تأییدشده‌ای از هیچ‌یک از {candidates.Count} سرور به دست نیامد. آخرین وضعیت: {lastFailure}");
    }
}
