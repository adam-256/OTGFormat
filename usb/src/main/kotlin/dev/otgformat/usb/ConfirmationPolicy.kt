package dev.otgformat.usb

/**
 * How firmly the user must confirm before a format proceeds.
 */
sealed class Confirmation {

    /**
     * Check the device identity and press the button.
     *
     * Used for devices in the size range of a boot stick, where the whole
     * exercise is routine and a typing ritual would train the user to dismiss
     * it without reading.
     */
    data class Acknowledge(val summary: String) : Confirmation()

    /**
     * Type [phrase] exactly before the button becomes live.
     *
     * Reserved for devices large enough that they are more likely to be
     * somebody's external drive than a stick to be wiped.
     */
    data class TypeToConfirm(val phrase: String, val summary: String, val reason: String) : Confirmation()
}

/**
 * Decides how much friction a given format deserves.
 *
 * Separated from the UI so the rule can be stated once and tested, rather than
 * living in a dialog's conditional.
 */
object ConfirmationPolicy {

    /**
     * Above this capacity, typing is required.
     *
     * 64 decimal GB, chosen against what devices actually report rather than
     * what they are sold as. A stick sold as "64GB" reports roughly 61.5 GB, so
     * it stays below the line and ordinary boot-stick work is unimpeded; a
     * 128 GB stick, and any external SSD worth protecting, lands above it.
     */
    const val TYPE_TO_CONFIRM_ABOVE_BYTES = 64_000_000_000L

    /** Typed when the volume has no label to type instead. */
    const val FALLBACK_PHRASE = "FORMAT"

    fun forTarget(target: UsbTarget, label: String?): Confirmation {
        val summary = target.describe()
        if (target.capacityBytes <= TYPE_TO_CONFIRM_ABOVE_BYTES) {
            return Confirmation.Acknowledge(summary)
        }
        // The label is the better phrase: typing it means the user has read the
        // options they set, not just copied a fixed word.
        val phrase = label?.trim()?.uppercase()?.ifEmpty { null } ?: FALLBACK_PHRASE
        return Confirmation.TypeToConfirm(
            phrase = phrase,
            summary = summary,
            reason = "This device reports ${target.capacityLabel()}. That is larger than a typical boot stick " +
                "and more likely to be an external drive. Type \"$phrase\" to confirm you mean to erase it.",
        )
    }

    /** Whether [typed] satisfies [confirmation]. Case- and space-insensitive. */
    fun isSatisfied(confirmation: Confirmation, typed: String?): Boolean = when (confirmation) {
        is Confirmation.Acknowledge -> true
        is Confirmation.TypeToConfirm -> typed?.trim()?.uppercase() == confirmation.phrase.uppercase()
    }
}
