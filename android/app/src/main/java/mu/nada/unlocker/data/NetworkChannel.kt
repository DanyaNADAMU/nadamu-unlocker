package mu.nada.unlocker.data

enum class NetworkChannel(val displayName: String) {
    USB("USB Tethering (RNDIS/NCM)"),
    HOTSPOT("Wi-Fi Hotspot"),
    LAN("Wi-Fi / Ethernet LAN")
}
