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
}
