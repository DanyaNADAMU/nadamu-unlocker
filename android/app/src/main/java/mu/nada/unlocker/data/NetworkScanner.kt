package mu.nada.unlocker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import mu.nada.unlocker.log.AppLogger
import mu.nada.unlocker.security.HostKeyManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

data class DiscoveredDevice(
    val ip: String,
    val port: Int = 22,
    val banner: String,
    val interfaceName: String,
    val channel: NetworkChannel = NetworkChannel.LAN
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
     * 2. If no cache hit, prioritize scanning in custom channelPriority order and return on first confirmed target.
     */
    suspend fun fastDiscovery(
        hostKeyManager: HostKeyManager? = null,
        port: Int = 22,
        timeoutMs: Int = 250
    ): DiscoveredDevice? = withContext(Dispatchers.IO) {
        val channelPriority = hostKeyManager?.getChannelPriority() ?: listOf(NetworkChannel.USB, NetworkChannel.HOTSPOT, NetworkChannel.LAN)
        val activeIfaces = getEligibleInterfaces().filter { iface ->
            val ch = classifyInterface(iface.name)
            hostKeyManager?.isChannelEnabled(ch) ?: true
        }

        if (activeIfaces.isEmpty()) {
            AppLogger.d(TAG, "Fast discovery: No active enabled interfaces detected")
            if (hostKeyManager?.isChannelEnabled(NetworkChannel.HOTSPOT) != false) {
                return@withContext probeCommonHotspotGateways(port, timeoutMs)
            }
            return@withContext null
        }

        // 1. Check cached IPs for active enabled interface channels
        if (hostKeyManager != null) {
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
                val probeDeferreds = cachedTargets.map { (ip, channel) ->
                    async { probeHost(ip, channel.name, port, timeoutMs) }
                }
                val cachedHits = probeDeferreds.awaitAll().filterNotNull()
                if (cachedHits.isNotEmpty()) {
                    val target = cachedHits.first()
                    AppLogger.i(TAG, "Fast discovery: Cache hit on ${target.ip}:${target.port} (${target.channel.name})")
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
            val found = scanSubnetForLuks(iface, maxHosts = 512, timeoutMs = timeoutMs)
            if (found.isNotEmpty()) {
                val device = found.first()
                if (hostKeyManager != null) {
                    hostKeyManager.setLastIp(device.channel, device.ip)
                }
                AppLogger.i(TAG, "Fast discovery: Found Dropbear target on ${device.ip}:${device.port} (${device.interfaceName})")
                return@withContext device
            }
        }

        // 3. Fallback hotspot gateways check
        if (hostKeyManager?.isChannelEnabled(NetworkChannel.HOTSPOT) != false) {
            probeCommonHotspotGateways(port, timeoutMs)
        } else {
            null
        }
    }

    private suspend fun probeCommonHotspotGateways(port: Int, timeoutMs: Int): DiscoveredDevice? = withContext(Dispatchers.IO) {
        val tasks = COMMON_HOTSPOT_SUBNETS.map { gatewayIp ->
            async {
                probeHost(gatewayIp, "hotspot-fallback", port, timeoutMs)
            }
        }
        val found = tasks.awaitAll().filterNotNull()
        found.firstOrNull()
    }

    /**
     * Scan network prefixes concurrently for port 22 dropbear/SSH.
     * If targetInterface is specified, only that interface is scanned.
     * If null, all active eligible interfaces are scanned concurrently.
     */
    suspend fun scanSubnetForLuks(
        targetInterface: NetworkInterface? = null,
        maxHosts: Int = 1024,
        timeoutMs: Int = 250,
        hostKeyManager: HostKeyManager? = null
    ): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val interfaces = if (targetInterface != null) {
            listOf(targetInterface)
        } else {
            getEligibleInterfaces().filter { iface ->
                val ch = classifyInterface(iface.name)
                hostKeyManager?.isChannelEnabled(ch) ?: true
            }
        }

        val allTasks = interfaces.flatMap { iface ->
            val channel = classifyInterface(iface.name)
            val ipv4Addresses = iface.interfaceAddresses.filter { it.address is Inet4Address }
            ipv4Addresses.flatMap { addr ->
                val inet4 = addr.address as Inet4Address
                val prefixLength = addr.networkPrefixLength
                val ipList = calculateSubnetIps(inet4, prefixLength, maxHosts)
                ipList.map { ip ->
                    async {
                        probeHost(ip, iface.name, 22, timeoutMs, channel)
                    }
                }
            }
        }.toMutableList()

        if (allTasks.isEmpty() && targetInterface == null) {
            if (hostKeyManager?.isChannelEnabled(NetworkChannel.HOTSPOT) != false) {
                COMMON_HOTSPOT_SUBNETS.forEach { gatewayIp ->
                    try {
                        val addr = InetAddress.getByName(gatewayIp) as? Inet4Address
                        if (addr != null) {
                            val ipList = calculateSubnetIps(addr, 24.toShort(), 254)
                            ipList.map { ip ->
                                allTasks.add(async { probeHost(ip, "hotspot", 22, timeoutMs, NetworkChannel.HOTSPOT) })
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        val results = allTasks.awaitAll().filterNotNull().distinctBy { "${it.ip}:${it.port}" }
        results
    }

    /**
     * Probe single host on port 22 with fast socket connect & banner check.
     */
    suspend fun probeHost(
        ip: String,
        ifaceName: String,
        port: Int = 22,
        timeoutMs: Int = 250,
        channel: NetworkChannel = classifyInterface(ifaceName)
    ): DiscoveredDevice? = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                socket.soTimeout = 400
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val banner = reader.readLine() ?: "SSH-Unknown"
                if (banner.contains("dropbear", ignoreCase = true) || banner.contains("SSH", ignoreCase = true)) {
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
