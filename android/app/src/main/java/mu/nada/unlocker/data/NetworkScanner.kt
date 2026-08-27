package mu.nada.unlocker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

data class DiscoveredDevice(
    val ip: String,
    val port: Int = 22,
    val banner: String,
    val interfaceName: String
)

class NetworkScanner {

    companion object {
        /**
         * Pure function to calculate candidate host IPs within an IPv4 CIDR subnet.
         * Handles prefix lengths (/16, /24, /28, /30, etc.) capped to maxHosts.
         */
        fun calculateSubnetIps(
            address: Inet4Address,
            prefixLength: Short,
            maxHosts: Int = 254
        ): List<String> {
            val addrBytes = address.address
            val ipInt = ((addrBytes[0].toInt() and 0xFF) shl 24) or
                    ((addrBytes[1].toInt() and 0xFF) shl 16) or
                    ((addrBytes[2].toInt() and 0xFF) shl 8) or
                    (addrBytes[3].toInt() and 0xFF)

            val prefix = prefixLength.toInt().coerceIn(1, 30)
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
    }

    /**
     * Find active USB tethering / RNDIS / Ethernet / WLAN interfaces.
     */
    fun getTetheringInterfaces(): List<NetworkInterface> {
        val tetheringPrefixes = listOf("rndis", "usb", "ncm", "eth", "wlan")
        val interfaces = mutableListOf<NetworkInterface>()
        try {
            val netInterfaces = NetworkInterface.getNetworkInterfaces()
            while (netInterfaces.hasMoreElements()) {
                val element = netInterfaces.nextElement()
                if (element.isUp && !element.isLoopback) {
                    val name = element.name.lowercase()
                    if (tetheringPrefixes.any { name.startsWith(it) }) {
                        interfaces.add(element)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return interfaces
    }

    /**
     * Scan network prefix for interface concurrently for port 22 dropbear.
     */
    suspend fun scanSubnetForLuks(targetInterface: NetworkInterface? = null): List<DiscoveredDevice> =
        withContext(Dispatchers.IO) {
            val iface = targetInterface ?: getTetheringInterfaces().firstOrNull()
                ?: return@withContext emptyList()

            val interfaceAddresses = iface.interfaceAddresses
            val ipv4Addr = interfaceAddresses.firstOrNull { it.address is Inet4Address }
                ?: return@withContext emptyList()

            val inet4 = ipv4Addr.address as Inet4Address
            val prefixLength = ipv4Addr.networkPrefixLength
            val ipList = calculateSubnetIps(inet4, prefixLength)

            val deferred = ipList.map { ip ->
                async {
                    probeHost(ip, iface.name)
                }
            }

            deferred.awaitAll().filterNotNull()
        }

    /**
     * Probe single host on port 22 with fast socket connect & banner check.
     */
    suspend fun probeHost(ip: String, ifaceName: String, port: Int = 22, timeoutMs: Int = 250): DiscoveredDevice? =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, port), timeoutMs)
                    socket.soTimeout = 400
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val banner = reader.readLine() ?: "SSH-Unknown"
                    if (banner.contains("dropbear", ignoreCase = true) || banner.contains("SSH", ignoreCase = true)) {
                        return@withContext DiscoveredDevice(ip, port, banner, ifaceName)
                    }
                }
            } catch (_: Exception) {
                // Host not reachable or port closed
            }
            null
        }
}
