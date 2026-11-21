package com.soprso.dhurandharai.safety

/**
 * On-device content safeguards for the local generative model.
 *
 * This is a deliberately small, deterministic filter: it never contacts a
 * network, never uploads a prompt or reply, and needs no extra model file.
 * It is a conservative heuristic (normalised phrase matching), not a trained
 * classifier and not a guarantee that unsafe output can never appear. It is
 * intended to reduce obvious violations of Google Play's AI-Generated Content
 * / Restricted Content / Child Endangerment / Deceptive Behavior policies.
 *
 * Both the user prompt and the model's reply are screened. A blocked prompt is
 * never sent to the inference engine. A blocked reply is never displayed or
 * persisted; the caller replaces it with [RESPONSE_REFUSAL].
 */
enum class SafetyCategory(val label: String, val guidance: String) {
    CHILD_SAFETY(
        "CHILD SAFETY",
        "Content that exploits or endangers children is never allowed.",
    ),
    SEXUAL(
        "SEXUAL CONTENT",
        "Explicit sexual content is not generated.",
    ),
    VIOLENCE(
        "VIOLENCE / GORE",
        "Graphic violence or instructions to harm people are not generated.",
    ),
    SELF_HARM(
        "SELF HARM",
        "Content that encourages self-injury or suicide is not generated.",
    ),
    ILLEGAL(
        "ILLEGAL ACTIVITY",
        "Help with illegal activity is not generated.",
    ),
    DECEPTION(
        "DECEPTION",
        "Impersonation, fraud and deceptive behaviour are not generated.",
    ),
    HATE(
        "HATE / HARASSMENT",
        "Hateful or harassing content is not generated.",
    ),
}

data class SafetyVerdict(
    val blocked: Boolean,
    val category: SafetyCategory? = null,
) {
    companion object {
        val ALLOW = SafetyVerdict(blocked = false)
    }
}

object ContentSafety {

    const val PROMPT_REFUSAL =
        "BLOCKED BY LOCAL SAFETY FILTER — I can't help with that request. " +
            "This check runs on your device; nothing was sent anywhere."
    const val RESPONSE_REFUSAL =
        "RESPONSE FILTERED — The model's reply was blocked by the on-device " +
            "safety filter, so it was not shown or saved. Try a different request."

    private data class Rule(val category: SafetyCategory, val patterns: List<Regex>)

    // Phrases are intentionally high-signal to limit false positives on
    // ordinary questions. The normaliser below also catches simple leetspeak.
    private val rules = listOf(
        Rule(
            SafetyCategory.CHILD_SAFETY,
            listOf(
                rx("child porn", "child sexual", "child sex", "child nude", "child naked"),
                rx("minor sex", "minor nude", "underage sex", "underage nude"),
                rx("groom a child", "grooming a child", "sexualize a child", "sexualise a child"),
                rx("csam", "child abuse material", "preteen sex", "teen porn"),
            ),
        ),
        Rule(
            SafetyCategory.SEXUAL,
            listOf(
                rx("write porn", "pornographic", "hardcore porn", "explicit sex", "sexual act"),
                rx("describe sexual acts", "erotic story", "nude body", "sex scene"),
            ),
        ),
        Rule(
            SafetyCategory.VIOLENCE,
            listOf(
                rx("graphic violence", "gore", "torture someone", "how to kill a person"),
                rx("make a bomb", "build a bomb", "kill a person", "kill someone", "murder someone"),
            ),
        ),
        Rule(
            SafetyCategory.SELF_HARM,
            listOf(
                rx("commit suicide", "kill myself", "end my life", "self harm"),
                rx("cut myself", "overdose", "how to die"),
            ),
        ),
        Rule(
            SafetyCategory.ILLEGAL,
            listOf(
                rx("how to hack", "hack into", "steal a car", "buy drugs"),
                rx("make meth", "sell drugs", "counterfeit money", "launder money"),
            ),
        ),
        Rule(
            SafetyCategory.DECEPTION,
            listOf(
                rx("impersonate a person", "impersonate someone", "pretend to be a doctor", "fake identity"),
                rx("write a phishing", "create a phishing", "write a scam", "scam email", "deceive people"),
            ),
        ),
        Rule(
            SafetyCategory.HATE,
            listOf(
                rx("hate speech", "racial slur", "ethnic slur", "slur against"),
                rx("harass someone", "bully someone", "attack people"),
            ),
        ),
    )

    /** Screen a user prompt before it reaches the engine. */
    fun screenPrompt(text: String): SafetyVerdict = screen(text)

    /** Screen a generated reply before it is shown or stored. */
    fun screenResponse(text: String): SafetyVerdict = screen(text)

    private fun screen(text: String): SafetyVerdict {
        if (text.isBlank()) return SafetyVerdict.ALLOW
        val normalised = normalise(text)
        for (rule in rules) {
            if (rule.patterns.any { it.containsMatchIn(normalised) }) {
                return SafetyVerdict(blocked = true, category = rule.category)
            }
        }
        return SafetyVerdict.ALLOW
    }

    private fun normalise(input: String): String {
        val lower = input.lowercase()
        val deobfuscated = buildString(lower.length) {
            for (ch in lower) {
                append(
                    when (ch) {
                        '@' -> 'a'
                        '$' -> 's'
                        '0' -> 'o'
                        '1', '!' -> 'l'
                        '3' -> 'e'
                        '4' -> 'a'
                        '5' -> 's'
                        '7' -> 't'
                        else -> ch
                    },
                )
            }
        }
        return deobfuscated.replace(Regex("[^a-z]+"), " ").trim()
    }

    private fun rx(vararg phrases: String): Regex = Regex(
        phrases.joinToString("|") { phrase ->
            phrase.split(' ').joinToString("\\s+") { Regex.escape(it) }
        },
    )
}
