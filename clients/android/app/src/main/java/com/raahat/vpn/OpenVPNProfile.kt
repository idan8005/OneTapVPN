package com.raahat.vpn

data class OpenVPNClientProfile(
    val server: String,
    val serverPort: Int,
    val network: String,
    val caCertificates: List<String>,
    val clientCertificates: List<String>,
    val clientKeys: List<String>,
    val controlWrapType: String?,
    val controlWrapKeys: List<String>,
    val controlWrapDirection: String?,
    val username: String,
    val password: String,
    val dataCiphers: List<String>,
    val dataCiphersFallback: String?,
    val auth: String?,
    val tlsCipher: String?,
    val tlsVersionMin: String?,
    val tlsServerName: String?,
    val tlsServerNameType: String?,
    val compression: String?,
    val compressionLzo: String?,
    val renegotiateIntervalSeconds: Int?,
    val renegotiateDisabled: Boolean,
    val pingIntervalSeconds: Int?,
    val pingRestartSeconds: Int?,
    val mssFix: Int?,
    val explicitExitNotify: Int?
) {
    fun toSingBoxConfig(): String {
        val tlsFields = mutableListOf<String>()
        tlsFields += "\"certificate\":" + jsonArray(caCertificates)
        tlsFields += "\"remote_certificate_tls\":\"server\""
        tlsCipher?.let { tlsFields += "\"cipher\":" + json(it) }
        tlsVersionMin?.let { tlsFields += "\"version_min\":" + json(it) }
        tlsServerName?.let { tlsFields += "\"server_name\":" + json(it) }
        tlsServerNameType?.let { tlsFields += "\"server_name_type\":" + json(it) }
        if (clientCertificates.isNotEmpty()) {
            tlsFields += "\"client_certificate\":" + jsonArray(clientCertificates)
            tlsFields += "\"client_key\":" + jsonArray(clientKeys)
        }
        if (controlWrapType != null && controlWrapKeys.isNotEmpty()) {
            val wrapFields = mutableListOf<String>()
            wrapFields += "\"type\":" + json(controlWrapType)
            wrapFields += "\"key\":" + jsonArray(controlWrapKeys)
            controlWrapDirection?.let { wrapFields += "\"direction\":" + json(it) }
            tlsFields += "\"control_wrap\":{" + wrapFields.joinToString(",") + "}"
        }

        val endpointFields = mutableListOf<String>()
        endpointFields += "\"type\":\"openvpn-client\""
        endpointFields += "\"tag\":\"ovpn-client\""
        endpointFields += "\"mode\":\"tls\""
        endpointFields += "\"server\":" + json(server)
        endpointFields += "\"server_port\":" + serverPort
        endpointFields += "\"network\":" + json(network)
        endpointFields += "\"username\":" + json(username)
        endpointFields += "\"password\":" + json(password)
        endpointFields += "\"auth_retry\":\"none\""
        endpointFields += "\"tls\":{" + tlsFields.joinToString(",") + "}"
        endpointFields += "\"allow_compression\":\"no\""
        endpointFields += "\"redirect_gateway\":true"
        endpointFields += "\"redirect_gateway_flags\":[\"def1\"]"
        endpointFields += "\"block_ipv6\":true"
        endpointFields += "\"mtu\":1400"
        if (dataCiphers.isNotEmpty()) endpointFields += "\"data_ciphers\":" + jsonArray(dataCiphers)
        dataCiphersFallback?.let { endpointFields += "\"data_ciphers_fallback\":" + json(it) }
        auth?.let { endpointFields += "\"auth\":" + json(it) }
        compression?.let { endpointFields += "\"compression\":" + json(it) }
        compressionLzo?.let { endpointFields += "\"compression_lzo\":" + json(it) }
        if (renegotiateDisabled) {
            endpointFields += "\"renegotiate_disabled\":true"
        } else {
            renegotiateIntervalSeconds?.let { endpointFields += "\"renegotiate_interval\":" + json(it.toString() + "s") }
        }
        pingIntervalSeconds?.let { endpointFields += "\"ping_interval\":" + json(it.toString() + "s") }
        pingRestartSeconds?.let { endpointFields += "\"ping_restart\":" + json(it.toString() + "s") }
        mssFix?.let { endpointFields += "\"mss_fix\":" + it }
        explicitExitNotify?.let { endpointFields += "\"explicit_exit_notify\":" + it }

        return buildString {
            append("{")
            append("\"log\":{\"level\":\"warn\",\"timestamp\":true},")
            append("\"dns\":{")
            append("\"servers\":[{")
            append("\"type\":\"openvpn\",")
            append("\"tag\":\"ovpn-dns\",")
            append("\"endpoint\":\"ovpn-client\",")
            append("\"accept_default_resolvers\":true,")
            append("\"accept_search_domain\":true")
            append("}],\"final\":\"ovpn-dns\"},")
            append("\"inbounds\":[{")
            append("\"type\":\"tun\",")
            append("\"tag\":\"raahat-tun\",")
            append("\"address\":[\"172.19.0.1/30\",\"fdfe:dcba:9876::1/126\"],")
            append("\"mtu\":1400,")
            append("\"auto_route\":true,")
            append("\"dns_mode\":\"hijack\",")
            append("\"dns_address\":[\"172.19.0.2\",\"fdfe:dcba:9876::2\"]")
            append("}],")
            append("\"endpoints\":[{")
            append(endpointFields.joinToString(","))
            append("}],")
            append("\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\"}],")
            append("\"route\":{\"auto_detect_interface\":true,\"final\":\"ovpn-client\"}")
            append("}")
        }
    }

    private fun jsonArray(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { json(it) }

    private fun json(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}

object OpenVPNProfileParser {
    private data class Remote(val host: String, val port: Int, val protocol: String?)

    fun parse(content: String, expectedHost: String, expectedIp: String): OpenVPNClientProfile {
        require(content.isNotBlank() && content.length <= 512 * 1024) {
            "حجم پروفایل OpenVPN معتبر نیست."
        }

        val blocks = linkedMapOf<String, MutableList<String>>()
        val remotes = mutableListOf<Remote>()
        var activeBlock: String? = null
        val activeContent = StringBuilder()
        var profileProtocol = ""
        var caPath = false
        var certPath = false
        var keyPath = false
        var tlsAuthPath = false
        var tlsCryptPath = false
        var tlsCryptV2Path = false
        var authUserPass = false
        var keyDirection: String? = null
        var cipher: String? = null
        var dataCiphers = emptyList<String>()
        var dataCiphersFallback: String? = null
        var auth: String? = null
        var tlsCipher: String? = null
        var tlsVersionMin: String? = null
        var tlsServerName: String? = null
        var tlsServerNameType: String? = null
        var compression: String? = null
        var compressionLzo: String? = null
        var renegotiateIntervalSeconds: Int? = null
        var renegotiateDisabled = false
        var pingIntervalSeconds: Int? = null
        var pingRestartSeconds: Int? = null
        var mssFix: Int? = null
        var explicitExitNotify: Int? = null

        val allowedBlocks = setOf("ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2")
        content.replace("\r\n", "\n").replace('\r', '\n').lines().forEach { rawLine ->
            val line = rawLine.trim()
            if (activeBlock != null) {
                if (line.equals("</" + activeBlock + ">", ignoreCase = true)) {
                    blocks.getOrPut(activeBlock!!) { mutableListOf() }.add(activeContent.toString().trim())
                    activeBlock = null
                    activeContent.setLength(0)
                } else {
                    activeContent.append(rawLine).append('\n')
                }
                return@forEach
            }

            if (line.startsWith("<") && line.endsWith(">") && !line.startsWith("</")) {
                val block = line.substring(1, line.length - 1).lowercase()
                require(block in allowedBlocks) { "بخش ناشناخته در پروفایل OpenVPN: " + block }
                activeBlock = block
                return@forEach
            }
            if (line.isBlank() || line.startsWith("#") || line.startsWith(";")) return@forEach

            val args = tokenize(line)
            if (args.isEmpty()) return@forEach
            when (args[0].lowercase()) {
                "proto" -> {
                    require(args.size >= 2) { "پروتکل OpenVPN مشخص نشده است." }
                    profileProtocol = normalizeProtocol(args[1])
                    require(profileProtocol.isNotBlank()) { "پروتکل OpenVPN پشتیبانی نمی‌شود." }
                }
                "remote" -> {
                    require(args.size >= 3) { "آدرس سرور در پروفایل ناقص است." }
                    val port = args[2].toIntOrNull()
                    if (port != null && port in 1..65535) {
                        val protocol = args.getOrNull(3)?.let(::normalizeProtocol)
                        if (args.size >= 4 && protocol.isNullOrBlank()) {
                            throw IllegalArgumentException("پروتکل سرور OpenVPN پشتیبانی نمی‌شود.")
                        }
                        remotes += Remote(args[1].trimEnd('.'), port, protocol)
                    }
                }
                "ca" -> caPath = args.size >= 2
                "cert" -> certPath = args.size >= 2
                "key" -> keyPath = args.size >= 2
                "tls-auth" -> tlsAuthPath = args.size >= 2
                "tls-crypt" -> tlsCryptPath = args.size >= 2
                "tls-crypt-v2" -> tlsCryptV2Path = args.size >= 2
                "key-direction" -> keyDirection = args.getOrNull(1)
                "auth-user-pass" -> {
                    require(args.size == 1) { "فایل جداگانه اطلاعات ورود OpenVPN پشتیبانی نمی‌شود." }
                    authUserPass = true
                }
                "cipher" -> cipher = args.getOrNull(1)
                "data-ciphers" -> dataCiphers = args.drop(1).flatMap { it.split(':', ',') }.filter { it.isNotBlank() }
                "data-ciphers-fallback" -> dataCiphersFallback = args.getOrNull(1)
                "auth" -> auth = args.getOrNull(1)
                "tls-cipher" -> tlsCipher = args.drop(1).joinToString(" ").ifBlank { null }
                "tls-version-min" -> {
                    val version = args.getOrNull(1)?.takeIf { it in setOf("1.0", "1.1", "1.2", "1.3") }
                    require(version != null) { "حداقل نسخه TLS در پروفایل معتبر نیست." }
                    tlsVersionMin = version
                }
                "verify-x509-name" -> {
                    tlsServerName = args.getOrNull(1)
                    tlsServerNameType = args.getOrNull(2) ?: "name"
                    require(tlsServerNameType in setOf("name", "subject", "name-prefix")) {
                        "روش بررسی نام گواهی پشتیبانی نمی‌شود."
                    }
                }
                "remote-cert-tls" -> require(args.getOrNull(1).equals("server", ignoreCase = true)) {
                    "پروفایل OpenVPN گواهی سرور را درخواست نمی‌کند."
                }
                "compress" -> {
                    val value = args.getOrNull(1)?.lowercase().orEmpty()
                    if (value in setOf("stub", "stub-v2")) compression = value
                    else require(value.isBlank() || value in setOf("no", "none", "off", "disabled")) {
                        "فشرده‌سازی قدیمی و ناامن در این پروفایل پشتیبانی نمی‌شود."
                    }
                }
                "comp-lzo" -> {
                    val value = args.getOrNull(1)?.lowercase().orEmpty()
                    require(value in setOf("", "no", "disabled", "off")) {
                        "فشرده‌سازی LZO در این پروفایل مجاز نیست."
                    }
                    compressionLzo = "no"
                }
                "reneg-sec" -> {
                    val value = args.getOrNull(1)?.toIntOrNull()
                    if (value != null) {
                        if (value == 0) renegotiateDisabled = true
                        else if (value in 60..86400) renegotiateIntervalSeconds = value
                    }
                }
                "ping" -> pingIntervalSeconds = args.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..3600 }
                "ping-restart" -> pingRestartSeconds = args.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..86400 }
                "mssfix" -> mssFix = args.getOrNull(1)?.toIntOrNull()?.takeIf { it in 576..1500 }
                "explicit-exit-notify" -> explicitExitNotify = args.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..10 } ?: 1
                "dev" -> require(args.getOrNull(1)?.equals("tun", ignoreCase = true) != false) {
                    "فقط رابط TUN در OpenVPN پشتیبانی می‌شود."
                }
                "pkcs12", "crl-verify", "secret" -> throw IllegalArgumentException(
                    "این نوع فایل خارجی در پروفایل OpenVPN پشتیبانی نمی‌شود."
                )
            }
        }

        require(activeBlock == null) { "بخش گواهی در پروفایل OpenVPN بسته نشده است." }
        val caCertificates = blocks["ca"].orEmpty().filter { it.isNotBlank() }
        val clientCertificates = blocks["cert"].orEmpty().filter { it.isNotBlank() }
        val clientKeys = blocks["key"].orEmpty().filter { it.isNotBlank() }
        require(caCertificates.isNotEmpty() && !caPath) {
            "پروفایل OpenVPN گواهی CA داخلی ندارد یا به فایل خارجی وابسته است."
        }
        require(clientCertificates.isEmpty() == clientKeys.isEmpty() && !certPath && !keyPath) {
            "گواهی و کلید کاربر باید هر دو داخل پروفایل موجود باشند."
        }

        val tlsAuth = blocks["tls-auth"].orEmpty().filter { it.isNotBlank() }
        val tlsCrypt = blocks["tls-crypt"].orEmpty().filter { it.isNotBlank() }
        val tlsCryptV2 = blocks["tls-crypt-v2"].orEmpty().filter { it.isNotBlank() }
        val wrapCount = listOf(tlsAuth, tlsCrypt, tlsCryptV2).count { it.isNotEmpty() }
        require(wrapCount <= 1) { "پروفایل چند نوع کلید TLS هم‌زمان دارد." }
        require(!(tlsAuthPath && tlsAuth.isEmpty()) &&
            !(tlsCryptPath && tlsCrypt.isEmpty()) &&
            !(tlsCryptV2Path && tlsCryptV2.isEmpty())) {
            "کلید TLS در پروفایل به فایل خارجی وابسته است."
        }

        val expected = expectedHost.lowercase().trimEnd('.')
        val expectedAddress = expectedIp.trim()
        val matchingRemotes = remotes.filter { candidate ->
            val host = candidate.host.lowercase().trimEnd('.')
            host == expected ||
                host == expected + ".opengw.net" ||
                expected == host + ".opengw.net" ||
                host.equals(expectedAddress, ignoreCase = true)
        }
        fun transportOf(remote: Remote): String =
            remote.protocol ?: profileProtocol.ifBlank { "udp" }

        // Prefer TCP when both transports are offered: TCP candidates can be
        // filtered with a bounded connect probe. UDP remains a fallback and
        // is only validated by a real OpenVPN handshake and traffic check.
        val remote = matchingRemotes.firstOrNull { transportOf(it) == "tcp" }
            ?: matchingRemotes.firstOrNull { transportOf(it) == "udp" }
            ?: throw IllegalArgumentException(
                "پروفایل OpenVPN سرور منطبق با فهرست VPN Gate ندارد."
            )
        val selectedNetwork = transportOf(remote)
        require(selectedNetwork in setOf("tcp", "udp")) {
            "پروتکل انتقال OpenVPN پشتیبانی نمی‌شود."
        }

        val wrapType: String?
        val wrapKeys: List<String>
        when {
            tlsAuth.isNotEmpty() -> {
                wrapType = "tls_auth"
                wrapKeys = tlsAuth
            }
            tlsCrypt.isNotEmpty() -> {
                wrapType = "tls_crypt"
                wrapKeys = tlsCrypt
            }
            tlsCryptV2.isNotEmpty() -> {
                wrapType = "tls_crypt_v2"
                wrapKeys = tlsCryptV2
            }
            else -> {
                wrapType = null
                wrapKeys = emptyList()
            }
        }

        val direction = when (keyDirection) {
            "1", "client" -> "client"
            "0", "server" -> "server"
            null, "" -> null
            else -> throw IllegalArgumentException("جهت کلید TLS در پروفایل معتبر نیست.")
        }
        require(wrapType == "tls_auth" || direction == null) {
            "جهت کلید فقط برای tls-auth قابل استفاده است."
        }

        if (dataCiphersFallback == null) dataCiphersFallback = cipher

        return OpenVPNClientProfile(
            server = remote.host,
            serverPort = remote.port,
            network = selectedNetwork,
            caCertificates = caCertificates,
            clientCertificates = clientCertificates,
            clientKeys = clientKeys,
            controlWrapType = wrapType,
            controlWrapKeys = wrapKeys,
            controlWrapDirection = direction,
            username = if (authUserPass) "vpn" else "",
            password = if (authUserPass) "vpn" else "",
            dataCiphers = dataCiphers,
            dataCiphersFallback = dataCiphersFallback,
            auth = auth,
            tlsCipher = tlsCipher,
            tlsVersionMin = tlsVersionMin,
            tlsServerName = tlsServerName,
            tlsServerNameType = tlsServerNameType,
            compression = compression,
            compressionLzo = compressionLzo,
            renegotiateIntervalSeconds = renegotiateIntervalSeconds,
            renegotiateDisabled = renegotiateDisabled,
            pingIntervalSeconds = pingIntervalSeconds,
            pingRestartSeconds = pingRestartSeconds,
            mssFix = mssFix,
            explicitExitNotify = explicitExitNotify.takeIf { selectedNetwork == "udp" }
        )
    }

    private fun normalizeProtocol(value: String): String = when (value.lowercase()) {
        "tcp", "tcp-client", "tcp4", "tcp6", "tcp4-client", "tcp6-client" -> "tcp"
        "udp", "udp4", "udp6" -> "udp"
        else -> ""
    }

    private fun tokenize(line: String): List<String> {
        val tokens = mutableListOf<String>()
        val token = StringBuilder()
        var quoted = false
        var escaped = false
        for (char in line) {
            if (escaped) {
                token.append(char)
                escaped = false
            } else when {
                char == '\\' -> escaped = true
                char == '"' -> quoted = !quoted
                char.isWhitespace() && !quoted -> {
                    if (token.isNotEmpty()) {
                        tokens += token.toString()
                        token.setLength(0)
                    }
                }
                else -> token.append(char)
            }
        }
        require(!quoted) { "عبارت نقل‌قول‌شده در پروفایل OpenVPN ناقص است." }
        if (token.isNotEmpty()) tokens += token.toString()
        return tokens
    }
}
