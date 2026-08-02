package com.droid.ble

class SeenPacketCache(
    private val maxEntries: Int = 1000,
    private val expiryMs: Long = 5 * 60 * 1000
) {
    private val seen = LinkedHashMap<String, Long>(16, 0.75f, true)

    @Synchronized
    fun hasSeen(packetId: String): Boolean {
        purgeExpired()
        return seen.containsKey(packetId)
    }

    @Synchronized
    fun markSeen(packetId: String) {
        purgeExpired()
        seen[packetId] = System.currentTimeMillis()
        while (seen.size > maxEntries) {
            val oldestKey = seen.keys.firstOrNull() ?: break
            seen.remove(oldestKey)
        }
    }

    @Synchronized
    fun clean() {
        purgeExpired()
    }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        seen.entries.removeAll { now - it.value > expiryMs }
    }
}