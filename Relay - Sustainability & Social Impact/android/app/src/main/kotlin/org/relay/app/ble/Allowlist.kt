package org.relay.app.ble

/** Demo chain allowlist: comma-separated node-id hex prefixes. Empty list = accept everyone. */
object Allowlist {
    fun parse(csv: String): List<String> =
        csv.split(',', ' ', ';', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    fun matches(csv: String, nodeIdHex: String): Boolean {
        val prefixes = parse(csv)
        if (prefixes.isEmpty()) return true
        val id = nodeIdHex.lowercase()
        return prefixes.any { id.startsWith(it) }
    }
}
