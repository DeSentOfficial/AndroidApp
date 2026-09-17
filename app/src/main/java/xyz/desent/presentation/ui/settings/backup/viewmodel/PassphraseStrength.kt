package xyz.desent.presentation.ui.settings.backup.viewmodel

/**
 * Lightweight, warn-only passphrase estimator for the backup wizard. It only
 * informs the user; it never gates the "Next" button (see [BackupUiState.passphraseReady]).
 */
enum class PassphraseStrength(val label: String) {
    EMPTY(""),
    WEAK("Weak"),
    FAIR("Fair"),
    GOOD("Good"),
    STRONG("Strong");

    companion object {
        fun estimate(passphrase: String): PassphraseStrength {
            if (passphrase.isEmpty()) return EMPTY
            var variety = 0
            if (passphrase.any { it.isLowerCase() }) variety++
            if (passphrase.any { it.isUpperCase() }) variety++
            if (passphrase.any { it.isDigit() }) variety++
            if (passphrase.any { !it.isLetterOrDigit() }) variety++
            return when {
                passphrase.length < 8 -> WEAK
                passphrase.length < 12 && variety < 3 -> FAIR
                passphrase.length < 12 -> GOOD
                variety >= 3 -> STRONG
                else -> GOOD
            }
        }
    }
}
