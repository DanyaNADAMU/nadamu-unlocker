package mu.nada.unlocker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Inet4Address

class NetworkScannerTest {

    @Test
    fun testCalculateSubnetIps_standard24() {
        val address = InetAddress.getByName("192.168.42.15") as Inet4Address
        val ips = NetworkScanner.calculateSubnetIps(address, 24.toShort())

        assertEquals(254, ips.size)
        assertEquals("192.168.42.1", ips.first())
        assertEquals("192.168.42.254", ips.last())
    }

    @Test
    fun testCalculateSubnetIps_largeSubnet22() {
        // /22 subnet has 1024 total IPs -> 1022 usable hosts
        val address = InetAddress.getByName("10.193.60.10") as Inet4Address
        val ips = NetworkScanner.calculateSubnetIps(address, 22.toShort(), maxHosts = 1024)

        assertEquals(1022, ips.size)
        assertEquals("10.193.60.1", ips.first())
        assertEquals("10.193.63.254", ips.last())
    }

    @Test
    fun testCalculateSubnetIps_small30() {
        // /30 subnet has 4 total addresses: .0 network, .1 host, .2 host, .3 broadcast
        val address = InetAddress.getByName("10.0.0.1") as Inet4Address
        val ips = NetworkScanner.calculateSubnetIps(address, 30.toShort())

        assertEquals(2, ips.size)
        assertEquals("10.0.0.1", ips[0])
        assertEquals("10.0.0.2", ips[1])
    }

    @Test
    fun testCalculateSubnetIps_subnet28() {
        // /28 subnet: 16 total IPs -> 14 usable host IPs
        val address = InetAddress.getByName("172.16.0.19") as Inet4Address
        val ips = NetworkScanner.calculateSubnetIps(address, 28.toShort())

        assertEquals(14, ips.size)
        assertEquals("172.16.0.17", ips.first())
        assertEquals("172.16.0.30", ips.last())
    }

    @Test
    fun testCalculateSubnetIps_cappedToMaxHostsFor16() {
        // /16 subnet would have 65534 hosts, must be capped to maxHosts
        val address = InetAddress.getByName("10.89.0.5") as Inet4Address
        val ips = NetworkScanner.calculateSubnetIps(address, 16.toShort(), maxHosts = 100)

        assertEquals(100, ips.size)
        assertEquals("10.89.0.1", ips.first())
        assertEquals("10.89.0.100", ips.last())
    }

    @Test
    fun testCalculateSubnetIps_zeroOrInvalidPrefixHandled() {
        val address = InetAddress.getByName("127.0.0.1") as Inet4Address
        val ips = NetworkScanner.calculateSubnetIps(address, 31.toShort())
        assertTrue(ips.isEmpty())
    }

    @Test
    fun testHotspotSubnets_standardAndroidAp() {
        // Standard Android AP uses 192.168.43.1 gateway
        val address = InetAddress.getByName("192.168.43.1") as Inet4Address
        val ips = NetworkScanner.calculateSubnetIps(address, 24.toShort())

        assertEquals(254, ips.size)
        assertEquals("192.168.43.1", ips.first())
        assertEquals("192.168.43.254", ips.last())
        assertTrue(NetworkScanner.COMMON_HOTSPOT_SUBNETS.contains("192.168.43.1"))
    }

    @Test
    fun testHotspotSubnets_wiFiDirectAndVendor() {
        assertTrue(NetworkScanner.COMMON_HOTSPOT_SUBNETS.contains("192.168.49.1"))
        assertTrue(NetworkScanner.COMMON_HOTSPOT_SUBNETS.contains("192.168.50.1"))
    }

    @Test
    fun testClassifyInterface() {
        assertEquals(NetworkChannel.USB, NetworkScanner.classifyInterface("rndis0"))
        assertEquals(NetworkChannel.USB, NetworkScanner.classifyInterface("usb0"))
        assertEquals(NetworkChannel.USB, NetworkScanner.classifyInterface("ncm0"))
        assertEquals(NetworkChannel.HOTSPOT, NetworkScanner.classifyInterface("ap0"))
        assertEquals(NetworkChannel.HOTSPOT, NetworkScanner.classifyInterface("softap0"))
        assertEquals(NetworkChannel.HOTSPOT, NetworkScanner.classifyInterface("swlan0"))
        assertEquals(NetworkChannel.LAN, NetworkScanner.classifyInterface("wlan0"))
        assertEquals(NetworkChannel.LAN, NetworkScanner.classifyInterface("eth0"))
    }

    @Test
    fun testDiscoveredDevice_hasLabelAndChannel() {
        val dev = DiscoveredDevice(
            ip = "192.168.42.2",
            port = 22,
            banner = "SSH-2.0-dropbear",
            interfaceName = "rndis0",
            channel = NetworkChannel.USB,
            fingerprint = "SHA256:abc",
            label = "Work Laptop"
        )
        assertEquals("Work Laptop", dev.label)
        assertEquals(NetworkChannel.USB, dev.channel)
        assertEquals("192.168.42.2", dev.ip)
    }
}
