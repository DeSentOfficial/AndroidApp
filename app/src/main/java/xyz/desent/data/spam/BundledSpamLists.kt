package xyz.desent.data.spam

import android.content.Context
import org.json.JSONObject

/**
 * Loads the bundled spam lists from `assets/lists/` (see LICENSES).
 *
 * Loaded once and cached for the process lifetime. The lists provide day-one
 * value before the NIP-51 manifest is fetched and before the user trains the
 * Bayesian layer (see refs/SPAM_FILTER_REFERENCE.md §"Bundled public lists").
 */
class BundledSpamLists(context: Context) : BundledSpamRules {

    private val assets = context.assets

    override val disposableDomains: Set<String> by lazy { loadDomains("lists/disposable_domains.txt") }

    /** High-signal subject regexes (lowercase source; matched case-insensitively). */
    override val subjectPatterns: List<String> by lazy { loadRulesJson().optJSONArray("subject_patterns")?.toStringList() ?: emptyList() }

    /** Body regexes (lowercase source; matched case-insensitively). */
    override val bodyPatterns: List<String> by lazy { loadRulesJson().optJSONArray("body_patterns")?.toStringList() ?: emptyList() }

    /** High-signal keyword set for density counting. */
    override val highSignalKeywords: Set<String> by lazy {
        loadRulesJson().optJSONArray("high_signal_keywords")?.toStringList()?.toSet() ?: emptySet()
    }

    private fun loadDomains(path: String): Set<String> {
        return runCatching {
            assets.open(path).bufferedReader().useLines { lines ->
                lines.map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toSet()
            }
        }.getOrElse {
            android.util.Log.w("BundledSpamLists", "Failed to load $path: ${it.message}")
            emptySet()
        }
    }

    private fun loadRulesJson(): JSONObject {
        return runCatching {
            assets.open("lists/spamassassin_rules.json").bufferedReader().use { it.readText() }
        }.mapCatching { JSONObject(it) }.getOrElse {
            android.util.Log.w("BundledSpamLists", "Failed to load spamassassin_rules.json: ${it.message}")
            JSONObject()
        }
    }

    private fun org.json.JSONArray.toStringList(): List<String> {
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            optString(i).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        return out
    }
}
