package org.relay.app.ui.i18n

import org.relay.core.payload.Lang

/** Strings for the language picker, Home and the Requests tab. Owned by the Home agent. */
class HomeStrings(private val lang: Lang) {
    // Pattern: one tr(...) per string, all five languages side by side. Replace this demo entry as you go.
    val title get() = tr(lang, "Relay", "Relay", "Relay", "Relay", "Relay")
}
