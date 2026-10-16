package org.relay.app

/**
 * Pure resolution of the gateway backend URL: emulator detection, build-time default, and the migration of the
 * old hard-coded emulator default on real phones. No Android types so it runs in JVM unit tests.
 */
object BackendDefaults {
    /** The host machine's loopback as seen from the Android emulator. */
    const val EMULATOR_URL = "http://10.0.2.2:8080"

    /** Robust emulator check over the `android.os.Build` strings (any of them may be null or empty). */
    fun isEmulator(fingerprint: String?, hardware: String?, product: String?): Boolean {
        val fp = fingerprint.orEmpty().lowercase()
        val hw = hardware.orEmpty().lowercase()
        val pr = product.orEmpty().lowercase()
        return fp.startsWith("generic") || fp.contains("emulator") || fp.contains("sdk_gphone") ||
            hw == "ranchu" || hw == "goldfish" ||
            pr.contains("sdk")
    }

    /** The default backend for this device: the emulator host alias on an emulator, else the build-time URL. */
    fun defaultFor(emulator: Boolean, buildDefault: String): String =
        if (emulator) EMULATOR_URL else buildDefault.trim().ifEmpty { EMULATOR_URL }

    /**
     * The backend to use given what is stored. Nothing stored (or blank) means "use the default". On a real
     * phone the legacy emulator default [EMULATOR_URL] is treated as unset, so installs that saved it before
     * the build-time default existed pick up the new one. Anything else the user chose is kept verbatim.
     */
    fun resolve(stored: String?, emulator: Boolean, buildDefault: String): String {
        val default = defaultFor(emulator, buildDefault)
        val s = stored?.trim().orEmpty()
        if (s.isEmpty()) return default
        if (!emulator && s.trimEnd('/') == EMULATOR_URL) return default
        return s
    }

    /** `host[:port]` of [url] for display, or the trimmed input when it does not parse. */
    fun hostOf(url: String): String {
        val t = url.trim()
        val rest = t.substringAfter("://", t)
        val authority = rest.substringBefore('/').substringBefore('?').substringAfterLast('@')
        return authority.ifEmpty { t }
    }
}
