package org.relay.app.ui.i18n

import org.relay.core.payload.Lang

/** Strings for the Network tab and Settings. Owned by the Network/Settings agent. */
class NetworkSettingsStrings(private val lang: Lang) {
    // Pattern: add a `val` per string; keep all five languages side by side.
    val settingsTitle get() = tr(lang, "Settings", "सेटिंग्स", "ସେଟିଂସ", "Settings", "Settings") // needs native review
}
