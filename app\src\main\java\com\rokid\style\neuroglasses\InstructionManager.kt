package com.rokid.style.neuroglasses

/**
 * Predefined instruction prefixes that are prepended to the user's transcribed text
 * before sending to the chat model — mirrors the iOS Instructions enum.
 */
object InstructionManager {

    data class Instruction(
        val id: String,
        val label: String,
        val prefix: String
    )

    val instructions: List<Instruction> = listOf(
        Instruction("none",       "None",                    ""),
        Instruction("summarize",  "Summarize",               "Summarize the following: "),
        Instruction("translate",  "Translate to English",    "Translate to English: "),
        Instruction("explain",    "Explain Simply",          "Explain in simple terms: "),
        Instruction("bullet",     "Bullet Points",           "Rewrite as concise bullet points: "),
        Instruction("proofread",  "Proofread",               "Proofread and correct the following text: "),
        Instruction("formal",     "Make Formal",             "Rewrite in a formal professional tone: ")
    )

    /** Returns the prefix string for a given instruction id; empty string if not found. */
    fun prefixFor(id: String): String =
        instructions.find { it.id == id }?.prefix ?: ""

    /** Apply the selected prefix to the raw transcribed text. */
    fun apply(instructionId: String, rawText: String): String {
        val prefix = prefixFor(instructionId)
        return if (prefix.isEmpty()) rawText else "$prefix$rawText"
    }
}
