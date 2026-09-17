package xyz.desent.data.local.preferences

/**
 * App theme modes. [SYSTEM] follows the device's dark/light setting,
 * [LIGHT] uses the soft DeSent light palette, [DARK] uses the brand dark palette.
 */
enum class ThemeMode(val displayName: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark")
}
