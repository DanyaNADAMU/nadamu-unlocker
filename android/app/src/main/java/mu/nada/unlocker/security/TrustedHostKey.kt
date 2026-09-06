package mu.nada.unlocker.security

data class TrustedHostKey(
    val fingerprint: String,
    val label: String = "Laptop",
    val addedTimestamp: Long = System.currentTimeMillis()
)
