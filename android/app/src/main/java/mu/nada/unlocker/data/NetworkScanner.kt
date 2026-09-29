package mu.nada.unlocker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import mu.nada.unlocker.log.AppLogger
import mu.nada.unlocker.security.FingerprintUtils
import mu.nada.unlocker.security.HostKeyManager
import net.schmizz.sshj.SSHClient
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.PublicKey

data class DiscoveredDevice(
    val ip: String,
    val port: Int = 22,
    val banner: String,
    val interfaceName: String,
    val channel: NetworkChannel = NetworkChannel.LAN,
    val fingerprint: String? = null,
    val label: String = "Laptop"
)

class NetworkScanner {

    companion object {
        private const val TAG = "NetworkScanner"

        val COMMON_HOTSPOT_SUBNETS = listOf(
            "192.168.43.1", // Standard Android AP
            "192.168.49.1", // Wi-Fi Direct / Hotspot
            "192.168.50.1"  // Vendor Hotspot
        )

        /**
         * Pure function to calculate candidate host IPs within an IPv4 CIDR subnet.
         * Handles prefix lengths (/16, /22, /23, /24, /28, /30, etc.) capped to maxHosts.
         */
        fun calculateSubnetIps(
            address: Inet4Address,
            prefixLength: Short,
            maxHosts: Int = 1024
        ): List<String> {
            if (prefixLength < 1 || prefixLength > 30) return emptyList()

            val addrBytes = address.address
            val ipInt = ((addrBytes[0].toInt() and 0xFF) shl 24) or
                    ((addrBytes[1].toInt() and 0xFF) shl 16) or
                    ((addrBytes[2].toInt() and 0xFF) shl 8) or
                    (addrBytes[3].toInt() and 0xFF)

            val prefix = prefixLength.toInt()
            val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
            val netInt = ipInt and mask
            val hostBits = 32 - prefix
            val totalHosts = if (hostBits >= 31) maxHosts else ((1 shl hostBits) - 2).coerceAtLeast(0)

            if (totalHosts <= 0) return emptyList()

            val count = minOf(totalHosts, maxHosts)
            val result = ArrayList<String>(count)

            for (i in 1..count) {
                val hostInt = netInt + i
                val ipStr = "${(hostInt ushr 24) and 0xFF}.${(hostInt ushr 16) and 0xFF}.${(hostInt ushr 8) and 0xFF}.${hostInt and 0xFF}"
                result.add(ipStr)
            }
            return result
        }

        fun classifyInterface(ifaceName: String): NetworkChannel {
            val name = ifaceName.lowercase()
            return when {
                name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") -> NetworkChannel.USB
                name.startsWith("ap") || name.startsWith("softap") || name.startsWith("swlan") || name.startsWith("tether") -> NetworkChannel.HOTSPOT
                else -> NetworkChannel.LAN
            }
        }
    }

    /**
     * Find active local LAN (Ethernet, Wi-Fi), AP/Hotspot, and USB tethering (RNDIS, NCM) interfaces.
     */
    fun getEligibleInterfaces(): List<NetworkInterface> {
        val targetPrefixes = listOf("eth", "en", "wlan", "rndis", "usb", "ncm", "ap", "softap", "swlan", "tether")
        val interfaces = mutableListOf<NetworkInterface>()
        try {
            val netInterfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (netInterfaces.hasMoreElements()) {
                val element = netInterfaces.nextElement()
                if (element.isUp && !element.isLoopback) {
                    val name = element.name.lowercase()
                    if (targetPrefixes.any { name.startsWith(it) }) {
                        val hasIpv4 = element.interfaceAddresses.any { it.address is Inet4Address }
                        if (hasIpv4) {
                            interfaces.add(element)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error enumerating network interfaces", e)
        }
        return interfaces
    }

    /**
     * Backward-compatible alias for getEligibleInterfaces.
     */
    fun getTetheringInterfaces(): List<NetworkInterface> = getEligibleInterfaces()

    /**
     * Fast Discovery Mode:
     * 1. Probe cached IPs for enabled active channels first (<200-300ms).
     * 2. Scans channels in priority order, filters by banner regex,
     *    and verifies candidate matches pinned trusted fingerprint.
     */
    suspend fun fastDiscovery(
        hostKeyManager: HostKeyManager? = null,
        timeoutMs: Int = 300
    ): DiscoveredDevice? = withContext(Dispatchers.IO) {
        if (hostKeyManager == null || !hostKeyManager.hasAnyTrustedKeys()) {
            AppLogger.d(TAG, "Fast discovery: No trusted keys configured in HostKeyManager")
            return@withContext null
        }

        val channelPriority = hostKeyManager.getChannelPriority()
        val targetPorts = hostKeyManager.getTargetPorts()
        val bannerRegex = hostKeyManager.getBannerRegex()

        val activeIfaces = getEligibleInterfaces().filter { iface ->
            val ch = classifyInterface(iface.name)
            hostKeyManager.isChannelEnabled(ch)
        }

        if (activeIfaces.isEmpty()) {
            AppLogger.d(TAG, "Fast discovery: No active enabled interfaces detected")
            if (hostKeyManager.isChannelEnabled(NetworkChannel.HOTSPOT)) {
                val fallbackHit = probeCommonHotspotGateways(targetPorts, bannerRegex, timeoutMs)
                if (fallbackHit != null) {
                    val fp = fallbackHit.fingerprint ?: fetchHostKeyFingerprint(fallbackHit.ip, fallbackHit.port)
                    if (fp != null && hostKeyManager.isFingerprintTrusted(fp)) {
                        val label = hostKeyManager.getLabelForFingerprint(fp)
                        return@withContext fallbackHit.copy(fingerprint = fp, label = label)
                    }
                }
            }
            return@withContext null
        }

        // 1. Check cached IPs for active enabled interface channels
        val cachedTargets = mutableListOf<Pair<String, NetworkChannel>>()
        for (iface in activeIfaces) {
            val channel = classifyInterface(iface.name)
            if (hostKeyManager.isChannelEnabled(channel)) {
                val cachedIp = hostKeyManager.getLastIp(channel)
                if (cachedIp != null) {
                    cachedTargets.add(Pair(cachedIp, channel))
                }
            }
        }

        if (cachedTargets.isNotEmpty()) {
            AppLogger.d(TAG, "Probing cached channel IPs: ${cachedTargets.joinToString { "${it.second.name}:${it.first}" }}")
            val probeDeferreds = cachedTargets.flatMap { (ip, channel) ->
                targetPorts.map { port ->
                    async { probeHost(ip, channel.name, port, timeoutMs, channel, bannerRegex) }
                }
            }
            val cachedHits = probeDeferreds.awaitAll().filterNotNull()
            if (cachedHits.isNotEmpty()) {
                val target = findTrustedDeviceCandidate(cachedHits, hostKeyManager)
                if (target != null) {
                    AppLogger.i(TAG, "Fast discovery: Cache hit on ${target.ip}:${target.port} (${target.label} - ${target.channel.name})")
                    return@withContext target
                }
            }
        }

        // 2. Prioritize channels according to user configuration
        val sortedIfaces = activeIfaces.sortedBy { iface ->
            val ch = classifyInterface(iface.name)
            val index = channelPriority.indexOf(ch)
            if (index >= 0) index else 999
        }

        for (iface in sortedIfaces) {
            val found = scanSubnetForLuks(targetInterface = iface, maxHosts = 1024, timeoutMs = timeoutMs, hostKeyManager = hostKeyManager)
            val candidate = found.firstOrNull()
            if (candidate != null) {
                hostKeyManager.setLastIp(candidate.channel, candidate.ip)
                AppLogger.i(TAG, "Fast discovery: Found matching target on ${candidate.ip}:${candidate.port} (${candidate.label} - ${candidate.interfaceName})")
                return@withContext candidate
            }
        }

        // 3. Fallback hotspot gateways check
        if (hostKeyManager.isChannelEnabled(NetworkChannel.HOTSPOT)) {
            val fallbackHit = probeCommonHotspotGateways(targetPorts, bannerRegex, timeoutMs)
            if (fallbackHit != null) {
                val fp = fallbackHit.fingerprint ?: fetchHostKeyFingerprint(fallbackHit.ip, fallbackHit.port)
                if (fp != null && hostKeyManager.isFingerprintTrusted(fp)) {
                    val label = hostKeyManager.getLabelForFingerprint(fp)
                    return@withContext fallbackHit.copy(fingerprint = fp, label = label)
                }
            }
        }
        null
    }

    private suspend fun findTrustedDeviceCandidate(
        devices: List<DiscoveredDevice>,
        hostKeyManager: HostKeyManager
    ): DiscoveredDevice? = withContext(Dispatchers.IO) {
        val probes = devices.map { dev ->
            async {
                val fp = dev.fingerprint ?: fetchHostKeyFingerprint(dev.ip, dev.port)
                if (fp != null && hostKeyManager.isFingerprintTrusted(fp)) {
                    val label = hostKeyManager.getLabelForFingerprint(fp)
                    dev.copy(fingerprint = fp, label = label)
                } else {
                    AppLogger.d(TAG, "Ignoring candidate host on ${dev.ip}:${dev.port} (untrusted fingerprint)")
                    null
                }
            }
        }
        probes.awaitAll().filterNotNull().firstOrNull()
    }

    suspend fun fetchHostKeyFingerprint(ip: String, port: Int = 22, timeoutMs: Int = 1500): String? = withContext(Dispatchers.IO) {
        try {
            var capturedKey: PublicKey? = null
            val ssh = SSHClient()
            ssh.addHostKeyVerifier(object : net.schmizz.sshj.transport.verification.HostKeyVerifier {
                override fun verify(hostname: String?, port: Int, key: PublicKey?): Boolean {
                    if (key != null) capturedKey = key
                    return true
                }
                override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
            })
            ssh.connectTimeout = timeoutMs
            ssh.timeout = timeoutMs
            try {
                ssh.connect(ip, port)
            } finally {
                try { ssh.disconnect() } catch (_: Exception) {}
            }
            capturedKey?.let { FingerprintUtils.getSha256Fingerprint(it) }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun probeCommonHotspotGateways(
        targetPorts: List<Int>,
        bannerRegex: String,
        timeoutMs: Int
    ): DiscoveredDevice? = withContext(Dispatchers.IO) {
        val tasks = COMMON_HOTSPOT_SUBNETS.flatMap { gatewayIp ->
            targetPorts.map { port ->
                async {
                    probeHost(gatewayIp, "hotspot-fallback", port, timeoutMs, NetworkChannel.HOTSPOT, bannerRegex)
                }
            }
        }
        val found = tasks.awaitAll().filterNotNull()
        found.firstOrNull()
    }

    /**
     * Scan network prefixes concurrently for dropbear/SSH matching port and banner regex.
     * Strictly verifies each device against HostKeyManager trusted keys.
     */
    suspend fun scanSubnetForLuks(
        targetInterface: NetworkInterface? = null,
        targetChannels: Set<NetworkChannel>? = null,
        maxHosts: Int = 1024,
        timeoutMs: Int = 300,
        hostKeyManager: HostKeyManager? = null
    ): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        if (hostKeyManager == null || !hostKeyManager.hasAnyTrustedKeys()) {
            AppLogger.d(TAG, "No trusted keys configured in HostKeyManager; skipping scan.")
            return@withContext emptyList()
        }

        val targetPorts = hostKeyManager.getTargetPorts()
        val bannerRegex = hostKeyManager.getBannerRegex()

        val interfaces = if (targetInterface != null) {
            listOf(targetInterface)
        } else {
            getEligibleInterfaces().filter { iface ->
                val ch = classifyInterface(iface.name)
                val matchesChannel = targetChannels == null || targetChannels.contains(ch)
                val isEnabled = hostKeyManager.isChannelEnabled(ch)
                matchesChannel && isEnabled
            }
        }

        val allTasks = interfaces.flatMap { iface ->
            val channel = classifyInterface(iface.name)
            val ipv4Addresses = iface.interfaceAddresses.filter { it.address is Inet4Address }
            ipv4Addresses.flatMap { addr ->
                val inet4 = addr.address as Inet4Address
                val prefixLength = addr.networkPrefixLength
                val ipList = calculateSubnetIps(inet4, prefixLength, maxHosts)
                ipList.flatMap { ip ->
                    targetPorts.map { port ->
                        async {
                            probeHost(ip, iface.name, port, timeoutMs, channel, bannerRegex)
                        }
                    }
                }
            }
        }.toMutableList()

        if (allTasks.isEmpty() && targetInterface == null) {
            val shouldProbeHotspot = (targetChannels == null || targetChannels.contains(NetworkChannel.HOTSPOT)) &&
                    hostKeyManager.isChannelEnabled(NetworkChannel.HOTSPOT)
            if (shouldProbeHotspot) {
                COMMON_HOTSPOT_SUBNETS.forEach { gatewayIp ->
                    try {
                        val addr = InetAddress.getByName(gatewayIp) as? Inet4Address
                        if (addr != null) {
                            val ipList = calculateSubnetIps(addr, 24.toShort(), 254)
                            ipList.forEach { ip ->
                                targetPorts.forEach { port ->
                                    allTasks.add(async { probeHost(ip, "hotspot", port, timeoutMs, NetworkChannel.HOTSPOT, bannerRegex) })
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        val probedCandidates = allTasks.awaitAll().filterNotNull().distinctBy { "${it.ip}:${it.port}" }

        // Filter: STRICTLY verify host key fingerprint and attach label. Untrusted are ignored.
        val verifiedDevices = probedCandidates.map { candidate ->
            async {
                val fp = candidate.fingerprint ?: fetchHostKeyFingerprint(candidate.ip, candidate.port)
                if (fp != null && hostKeyManager.isFingerprintTrusted(fp)) {
                    val label = hostKeyManager.getLabelForFingerprint(fp)
                    AppLogger.d(TAG, "Discovered trusted host: ${candidate.ip}:${candidate.port} ($label)")
                    candidate.copy(fingerprint = fp, label = label)
                } else {
                    AppLogger.d(TAG, "Ignoring untrusted Dropbear host on ${candidate.ip}:${candidate.port} (fp=${fp ?: "none"})")
                    null
                }
            }
        }.awaitAll().filterNotNull()

        verifiedDevices
    }

    /**
     * Probe single host on target port with fast socket connect & banner regex check.
     */
    suspend fun probeHost(
        ip: String,
        ifaceName: String,
        port: Int = 22,
        timeoutMs: Int = 300,
        channel: NetworkChannel = classifyInterface(ifaceName),
        bannerRegex: String = ".*dropbear.*"
    ): DiscoveredDevice? = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                socket.soTimeout = 400
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val banner = reader.readLine() ?: return@withContext null

                val matches = try {
                    banner.contains("dropbear", ignoreCase = true) || banner.matches(Regex(bannerRegex, RegexOption.IGNORE_CASE))
                } catch (_: Exception) {
                    banner.contains("dropbear", ignoreCase = true)
                }

                if (matches) {
                    return@withContext DiscoveredDevice(
                        ip = ip,
                        port = port,
                        banner = banner,
                        interfaceName = ifaceName,
                        channel = channel
                    )
                }
            }
        } catch (_: Exception) {
            // Host unreachable or port closed
        }
        null
    }
}
