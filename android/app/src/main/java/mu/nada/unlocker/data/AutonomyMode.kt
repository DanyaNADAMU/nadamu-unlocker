package mu.nada.unlocker.data

enum class AutonomyMode(val displayName: String, val description: String) {
    MANUAL(
        displayName = "Manual (On-Demand)",
        description = "Unlock only when you press the button in app or tap Quick Settings tile"
    ),
    SEMI_AUTO(
        displayName = "Semi-Automatic",
        description = "Monitors events (screen unlock, USB, Hotspot, Wi-Fi) and prompts with a notification to unlock"
    ),
    AUTO(
        displayName = "Full Automatic",
        description = "Monitors events and automatically unlocks trusted laptops without user interaction"
    )
}
