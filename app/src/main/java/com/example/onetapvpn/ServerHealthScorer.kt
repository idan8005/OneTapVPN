package com.example.onetapvpn

import kotlin.math.ln

internal data class ServerQualitySample(
    val tag: String,
    val pingMs: Int,
    val speedMbps: Double,
    val successRate: Double = 1.0,
    val jitterMs: Int = 0
)

internal object ServerHealthScorer {
    fun rank(candidates: List<ServerQualitySample>): List<String> {
        val online = candidates.filter { it.pingMs > 0 && it.successRate > 0.0 }
        if (online.isEmpty()) return emptyList()

        val fastestSpeed = online.filter { it.speedMbps > 0.0 }
            .maxOfOrNull { it.speedMbps } ?: 0.0
        val logSpeedCeiling = if (fastestSpeed > 0.0) ln(1.0 + fastestSpeed) else 0.0
        val lowestPing = online.minOf { it.pingMs }.coerceAtLeast(1)

        return online.sortedByDescending { candidate ->
            // Logarithmic normalization stops one extreme speed reading from
            // overwhelming latency and stability for every other candidate.
            val speedScore = if (
                logSpeedCeiling > 0.0 && candidate.speedMbps > 0.0
            ) {
                (ln(1.0 + candidate.speedMbps.coerceAtMost(fastestSpeed)) / logSpeedCeiling)
                    .coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val latencyScore =
                (lowestPing.toDouble() / candidate.pingMs).coerceIn(0.0, 1.0)
            val reliabilityScore = candidate.successRate.coerceIn(0.0, 1.0)
            val jitterScore =
                (1.0 / (1.0 + candidate.jitterMs.coerceAtLeast(0) / 80.0))
                    .coerceIn(0.0, 1.0)

            speedScore * 0.45 + latencyScore * 0.30 +
                reliabilityScore * 0.20 + jitterScore * 0.05
        }.map { it.tag }
    }

    fun selectBest(candidates: List<ServerQualitySample>): String? =
        rank(candidates).firstOrNull()
}