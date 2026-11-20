package com.example.onetapvpn

internal data class ServerQualitySample(
    val tag: String,
    val pingMs: Int,
    val speedMbps: Double,
    val successRate: Double = 1.0,
    val jitterMs: Int = 0
)

internal object ServerHealthScorer {
    fun selectBest(candidates: List<ServerQualitySample>): String? {
        val online = candidates.filter { it.pingMs > 0 && it.successRate > 0.0 }
        if (online.isEmpty()) return null

        val speedMeasured = online.filter { it.speedMbps > 0.0 }
        val pool = speedMeasured.ifEmpty { online }
        val lowestPing = online.minOf { it.pingMs }.coerceAtLeast(1)
        val fastestSpeed = speedMeasured.maxOfOrNull { it.speedMbps } ?: 0.0

        return pool.maxByOrNull { candidate ->
            val latencyScore = (lowestPing.toDouble() / candidate.pingMs).coerceIn(0.0, 1.0)
            val reliabilityScore = candidate.successRate.coerceIn(0.0, 1.0)
            val jitterScore = (1.0 / (1.0 + candidate.jitterMs.coerceAtLeast(0) / 80.0))
                .coerceIn(0.0, 1.0)
            if (fastestSpeed > 0.0) {
                val speedScore = (candidate.speedMbps / fastestSpeed).coerceIn(0.0, 1.0)
                speedScore * 0.55 + latencyScore * 0.25 +
                    reliabilityScore * 0.15 + jitterScore * 0.05
            } else {
                latencyScore * 0.65 + reliabilityScore * 0.25 + jitterScore * 0.10
            }
        }?.tag
    }
}
