package org.relay.app.ui.i18n

import org.relay.core.payload.Lang

/** Strings for Request detail (timeline is in [CommonStrings]). Owned by the Request agent. */
class RequestStrings(private val lang: Lang) {
    // Pattern: add a `val` per string; keep all five languages side by side.
    val title get() = tr(lang, "Request", "अनुरोध", "ଅନୁରୋଧ", "Kahilingan", "Hangyo") // needs native review
}
