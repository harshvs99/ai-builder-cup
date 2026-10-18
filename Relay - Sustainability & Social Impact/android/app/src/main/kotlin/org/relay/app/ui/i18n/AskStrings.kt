package org.relay.app.ui.i18n

import org.relay.core.payload.Lang

/** Strings for New request (voice/text) and Check and send. Owned by the Ask agent. */
class AskStrings(private val lang: Lang) {
    // Pattern: a `val` for fixed text, a `fun` for text with parameters; mark non-English `// needs native review`.
    val newRequestTitle get() = tr(lang, "New request", "नया अनुरोध", "ନୂଆ ଅନୁରୋଧ", "Bagong kahilingan", "Bag-o nga hangyo") // needs native review
    fun stepCounter(step: Int, total: Int) = tr(lang, "$step of $total", "$total में से $step", "$total ରୁ $step", "$step sa $total", "$step han $total") // needs native review
}
