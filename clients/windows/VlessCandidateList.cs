using System;
using System.Collections.Generic;
using System.Linq;

namespace RaahatVPN;

public static class VlessCandidateList
{
    public const int MaximumCandidates = 5;
    public const int MaximumLinkLength = 8192;

    public static IReadOnlyList<string> Parse(string? input)
    {
        if (string.IsNullOrWhiteSpace(input))
            throw new FormatException("حداقل یک لینک سرور VLESS وارد کنید.");

        var candidates = input
            .Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries)
            .Select(line => line.Trim())
            .Where(line => line.Length > 0 && !line.StartsWith("#", StringComparison.Ordinal))
            .Distinct(StringComparer.Ordinal)
            .ToArray();

        if (candidates.Length == 0)
            throw new FormatException("هیچ لینک سرور معتبری وارد نشده است.");
        if (candidates.Length > MaximumCandidates)
            throw new FormatException($"حداکثر {MaximumCandidates} لینک پشتیبان، هرکدام در یک خط، قابل واردکردن است.");
        if (candidates.Any(link => link.Length > MaximumLinkLength))
            throw new FormatException("یکی از لینک‌های سرور بیش از اندازه طولانی است.");
        if (candidates.Any(link => !link.StartsWith("vless://", StringComparison.OrdinalIgnoreCase)))
            throw new FormatException("همه موارد باید لینک VLESS باشند؛ هر لینک را در یک خط جدا وارد کنید.");

        return candidates;
    }
}
