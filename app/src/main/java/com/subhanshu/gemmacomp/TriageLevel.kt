package com.subhanshu.gemmacomp

import android.util.Log
import androidx.compose.ui.graphics.Color

/**
 * START triage classification levels.
 * Each level maps to a distinct color used in both the UI banner and visual overlays.
 */
enum class TriageLevel(
    val label: String,
    val color: Color,
    val textColor: Color,
    val description: String
) {
    RED(
        label = "IMMEDIATE",
        color = Color(0xFFE53935),
        textColor = Color.White,
        description = "Life-threatening — requires immediate intervention"
    ),
    YELLOW(
        label = "DELAYED",
        color = Color(0xFFFDD835),
        textColor = Color.Black,
        description = "Serious but can wait — not immediately life-threatening"
    ),
    GREEN(
        label = "MINOR",
        color = Color(0xFF43A047),
        textColor = Color.White,
        description = "Walking wounded — can wait for treatment"
    ),
    BLACK(
        label = "DECEASED",
        color = Color(0xFF212121),
        textColor = Color.White,
        description = "Deceased or non-survivable injuries"
    ),
    UNKNOWN(
        label = "ASSESSING",
        color = Color(0xFF546E7A),
        textColor = Color.White,
        description = "Triage assessment in progress"
    );

    companion object {
        /**
         * Parse triage level from LLM response text.
         *
         * ONLY looks at the "TRIAGE_LEVEL:" line to avoid false matches
         * from words like "immediately" appearing in the response body.
         */
        fun fromResponseText(text: String): TriageLevel {
            Log.d("TriageLevel", "Parsing response text: ${text.take(120)}...")

            // Step 1: Extract ONLY the TRIAGE_LEVEL line
            val triageLine = text.lines()
                .firstOrNull { it.trimStart().startsWith("TRIAGE_LEVEL:", ignoreCase = true) }

            if (triageLine != null) {
                val levelValue = triageLine
                    .substringAfter(":", "")
                    .trim()
                    .uppercase()

                val level = when {
                    levelValue.startsWith("BLACK") || levelValue.contains("DECEASED") -> BLACK
                    levelValue.startsWith("RED") || levelValue.contains("IMMEDIATE") -> RED
                    levelValue.startsWith("YELLOW") || levelValue.contains("DELAYED") -> YELLOW
                    levelValue.startsWith("GREEN") || levelValue.contains("MINOR") -> GREEN
                    else -> UNKNOWN
                }
                Log.d("TriageLevel", "Parsed from TRIAGE_LEVEL line: '$levelValue' -> $level")
                return level
            }

            // Step 2: Fallback — scan first 2 lines only (not the full response body)
            val header = text.lines().take(2).joinToString(" ").uppercase()
            val level = when {
                header.contains("\\bBLACK\\b".toRegex()) -> BLACK
                header.contains("\\bRED\\b".toRegex()) -> RED
                header.contains("\\bYELLOW\\b".toRegex()) -> YELLOW
                header.contains("\\bGREEN\\b".toRegex()) -> GREEN
                else -> UNKNOWN
            }
            Log.d("TriageLevel", "Parsed from fallback header: $level")
            return level
        }
    }
}
