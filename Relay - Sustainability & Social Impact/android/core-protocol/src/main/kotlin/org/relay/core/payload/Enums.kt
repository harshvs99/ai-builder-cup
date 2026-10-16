package org.relay.core.payload

enum class Category(val code: Int) {
    MEDICAL(0), RESCUE(1), WATER(2), FOOD(3), SHELTER(4), MISSING_PERSON(5), OTHER(6);

    companion object {
        fun fromCode(code: Int): Category? = entries.firstOrNull { it.code == code }
    }
}

enum class AckStatus(val code: Int) {
    RECEIVED(0), DISPATCHED(1), ON_THE_WAY(2), RESOLVED(3);

    companion object {
        fun fromCode(code: Int): AckStatus? = entries.firstOrNull { it.code == code }
    }
}

/** Language of the requester's text. Wire code plus ISO-ish string code. */
enum class Lang(val code: Int, val tag: String) {
    EN(0, "en"), HI(1, "hi"), OR(2, "or"), TL(3, "tl"), WAR(4, "war"), CEB(5, "ceb");

    companion object {
        fun fromCode(code: Int): Lang? = entries.firstOrNull { it.code == code }
        fun fromTag(tag: String): Lang? = entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) }
    }
}

enum class VulnFlag(val bit: Int) {
    ELDERLY(1), CHILD(2), PREGNANT(4), INJURED(8), TRAPPED(16);

    companion object {
        fun toMask(flags: Collection<VulnFlag>): Int = flags.fold(0) { m, f -> m or f.bit }
        fun fromMask(mask: Int): Set<VulnFlag> =
            entries.filterTo(java.util.EnumSet.noneOf(VulnFlag::class.java)) { mask and it.bit != 0 }
    }
}
