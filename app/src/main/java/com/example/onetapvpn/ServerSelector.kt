package com.example.onetapvpn

/**
 * Pure helpers for picking the fastest Cloudflare WARP endpoint. Only single-peer WARP profiles are
 * touched; profiles the user imported for their own server are never rewritten.
 */
internal object ServerSelector {
    /** Well-known WARP endpoint addresses (UDP 2408); all are served by the nearest Cloudflare site. */
    val CANDIDATE_ENDPOINTS = listOf(
        "162.159.192.1:2408",
        "162.159.193.1:2408",
        "188.114.96.1:2408",
        "188.114.97.1:2408",
        "188.114.98.1:2408",
        "188.114.99.1:2408"
    )

    private val ENDPOINT_LINE = Regex("(?im)^([ \\t]*Endpoint[ \\t]*=[ \\t]*)(.*)$")
    private val PEER_SECTION = Regex("(?im)^[ \\t]*\\[Peer\\]")

    fun currentEndpoint(profile: String): String? =
        ENDPOINT_LINE.find(profile)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }

    fun isWarpProfile(profile: String): Boolean {
        if (PEER_SECTION.findAll(profile).count() != 1) return false
        val endpoint = currentEndpoint(profile) ?: return false
        return endpoint.startsWith("162.159.") ||
            endpoint.startsWith("188.114.") ||
            endpoint.substringBeforeLast(':').endsWith("cloudflareclient.com")
    }

    fun withEndpoint(profile: String, endpoint: String): String =
        ENDPOINT_LINE.replace(profile) { match -> match.groupValues[1] + endpoint }

    /** The remembered best endpoint first, then the profile's own endpoint, then the known list. */
    fun orderedCandidates(profile: String, preferred: String?): List<String> =
        (listOfNotNull(preferred, currentEndpoint(profile)) + CANDIDATE_ENDPOINTS).distinct()
}
