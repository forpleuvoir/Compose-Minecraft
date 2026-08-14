package androidx.compose.ui.text.intl

import androidx.compose.runtime.Immutable

/**
 * A `Locale` object represents a specific geographical, political, or cultural region.
 */
@Immutable
class Locale internal constructor(internal val javaLocale: java.util.Locale) {
    companion object {
        /** Returns a [Locale] object which represents current locale */
        val current: Locale = Locale(java.util.Locale.getDefault())
    }

    /** Create Locale object from a language tag. */
    constructor(languageTag: String) : this(java.util.Locale.forLanguageTag(languageTag))

    /** The ISO 639 compliant language code. */
    val language: String get() = javaLocale.language

    /** The ISO 15924 compliant 4-letter script code. */
    val script: String get() = javaLocale.script

    /** The ISO 3166 compliant region code. */
    val region: String get() = javaLocale.country

    /** Returns a IETF BCP47 compliant language tag representation of this Locale. */
    fun toLanguageTag(): String = javaLocale.toLanguageTag()

    override fun equals(other: Any?): Boolean = other is Locale && javaLocale == other.javaLocale

    override fun hashCode(): Int = javaLocale.hashCode()

    override fun toString(): String = javaLocale.toString()
}