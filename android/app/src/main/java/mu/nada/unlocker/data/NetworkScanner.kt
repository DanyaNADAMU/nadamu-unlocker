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

    /**
     * Find active USB tethering / RNDIS / Ethernet interfaces.
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
     * Scan network prefix (subnet /24) concurrently for port 22 dropbear.
     */
    suspend fun scanSubnetForLuks(targetInterface: NetworkInterface? = null): List<DiscoveredDevice> =
        withContext(Dispatchers.IO) {
            val iface = targetInterface ?: getTetheringInterfaces().firstOrNull()
                ?: return@withContext emptyList()

            val interfaceAddresses = iface.interfaceAddresses
            val ipv4Addr = interfaceAddresses.firstOrNull { it.address is Inet4Address }
                ?: return@withContext emptyList()

            val hostAddress = ipv4Addr.address.hostAddress ?: return@withContext emptyList()
            val subnetPrefix = hostAddress.substringBeforeLast(".")

            // Concurrently probe all 254 addresses in subnet
            val deferred = (1..254).map { i ->
                val ip = "$subnetPrefix.$i"
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
