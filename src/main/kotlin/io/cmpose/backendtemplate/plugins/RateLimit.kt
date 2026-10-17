package io.cmpose.backendtemplate.plugins

import io.cmpose.backendtemplate.auth.identityKeyOrNull
import io.cmpose.backendtemplate.config.AppConfig
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.RateLimitProviderConfig
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.time.Duration.Companion.minutes

/** The strict limiter (`RATE_LIMIT_SENSITIVE_PER_MINUTE`); wrap uploads and other abuse-prone routes in it. */
val SENSITIVE_LIMIT = RateLimitName("sensitive")

const val FORWARDED_FOR_HEADER = "X-Forwarded-For"

private const val IPV4_BITS = 32
private const val IPV4_BYTES = 4
private const val IPV6_BITS = 128
private const val BITS_PER_BYTE = 8
private const val BYTE_MASK = 0xFF
private val IPV4_LITERAL = Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")
private val IPV6_CHARS = Regex("""^[0-9A-Fa-f:.]+$""")

/**
 * Parses an IP literal and never resolves a name: a header value must not trigger a DNS lookup. `localhost` is
 * accepted only for the socket peer (the test engine reports it), never for a header value.
 */
internal fun parseIp(text: String, allowLocalhost: Boolean = false): InetAddress? {
    val value = text.trim().removePrefix("[").removeSuffix("]")
    return when {
        allowLocalhost && value == "localhost" -> InetAddress.getLoopbackAddress()
        IPV4_LITERAL.matches(value) ->
            InetAddress.getByAddress(value.split('.').map { it.toInt().toByte() }.toByteArray())
        // With a colon in it, InetAddress takes the text as an IPv6 literal and never looks it up.
        ':' in value && IPV6_CHARS.matches(value) -> try {
            InetAddress.getByName(value)
        } catch (_: UnknownHostException) {
            null
        }
        else -> null
    }
}

/** An address range in CIDR form (`10.0.0.0/8`, `fd00::/8`); a bare address is a range of one. */
internal class Cidr private constructor(private val network: ByteArray, private val prefixBits: Int) {
    fun contains(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size != network.size) return false
        val fullBytes = prefixBits / BITS_PER_BYTE
        for (i in 0 until fullBytes) if (bytes[i] != network[i]) return false
        val restBits = prefixBits % BITS_PER_BYTE
        if (restBits == 0) return true
        val mask = (BYTE_MASK shl (BITS_PER_BYTE - restBits)) and BYTE_MASK
        return (bytes[fullBytes].toInt() and mask) == (network[fullBytes].toInt() and mask)
    }

    companion object {
        /** @throws IllegalArgumentException when [text] is not an IP literal with an optional in-range prefix. */
        fun parse(text: String): Cidr {
            val parts = text.trim().split('/')
            require(parts.size <= 2) { "Invalid CIDR in TRUSTED_PROXIES: $text" }
            val address = requireNotNull(parseIp(parts[0])) { "Invalid CIDR in TRUSTED_PROXIES: $text" }
            val maxBits = if (address.address.size == IPV4_BYTES) IPV4_BITS else IPV6_BITS
            val bits = if (parts.size == 2) parts[1].toIntOrNull() else maxBits
            require(bits != null && bits in 0..maxBits) { "Invalid CIDR in TRUSTED_PROXIES: $text" }
            return Cidr(address.address, bits)
        }
    }
}

/**
 * The client's address. `X-Forwarded-For` is believed only when the socket peer is a trusted proxy ([trusted]
 * CIDRs); then the rightmost hop that is not itself trusted is the client, since every hop to its right was added by a
 * proxy we trust and everything to its left may be forged. A hop that is not an IP literal ends the walk at the
 * proxy that added it. An untrusted socket peer is the client itself, whatever the header says.
 */
fun ApplicationCall.clientIp(trusted: List<String>): String = clientIpOf(trusted.map(Cidr::parse))

internal fun ApplicationCall.clientIpOf(trusted: List<Cidr>): String {
    val peerText = request.local.remoteAddress
    val peer = parseIp(peerText, allowLocalhost = true) ?: return peerText
    if (trusted.none { it.contains(peer) }) return peer.hostAddress
    val hops = request.headers.getAll(FORWARDED_FOR_HEADER).orEmpty()
        .flatMap { it.split(',') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    var client = peer
    for (hop in hops.asReversed()) {
        client = parseIp(hop) ?: return client.hostAddress
        if (trusted.none { it.contains(client) }) return client.hostAddress
    }
    return client.hostAddress
}

/**
 * Two limiters per minute: the global one ([AppConfig.rateLimitPerMinute]) on every request and [SENSITIVE_LIMIT]
 * ([AppConfig.rateLimitSensitivePerMinute]) on the routes wrapped in it. A caller with a valid token is counted by
 * their verified identity (hashed, see [identityKeyOrNull]; no database lookup, so a flood is refused before it reaches
 * the database), anyone else by [clientIp]. Over the limit is a 429 `RATE_LIMITED` with `Retry-After`.
 *
 * @throws IllegalArgumentException at startup when `TRUSTED_PROXIES` holds something that is not a CIDR.
 */
fun Application.installRateLimit(config: AppConfig) {
    val trusted = config.trustedProxies.map(Cidr::parse)
    fun RateLimitProviderConfig.limit(perMinute: Int) {
        rateLimiter(limit = perMinute, refillPeriod = 1.minutes)
        requestKey { call -> call.identityKeyOrNull()?.let { "user:$it" } ?: "ip:${call.clientIpOf(trusted)}" }
    }
    install(RateLimit) {
        global { limit(config.rateLimitPerMinute) }
        register(SENSITIVE_LIMIT) { limit(config.rateLimitSensitivePerMinute) }
    }
}
