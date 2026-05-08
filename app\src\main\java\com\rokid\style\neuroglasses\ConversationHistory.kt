package com.rokid.style.neuroglasses

/**
 * Holds the rolling conversation history sent with every chat request.
 * Thread-safe via @Synchronized.
 */
class ConversationHistory {

    data class Message(val role: String, val content: String)

    private val messages = mutableListOf<Message>()

    /** Maximum number of exchange pairs (user + assistant) to keep. */
    private val maxPairs = 10

    @Synchronized
    fun add(role: String, content: String) {
        messages.add(Message(role, content))
        // Trim: keep at most maxPairs*2 messages (not counting system)
        val nonSystem = messages.filter { it.role != "system" }
        if (nonSystem.size > maxPairs * 2) {
            val excess = nonSystem.size - maxPairs * 2
            var removed = 0
            val iter = messages.iterator()
            while (iter.hasNext() && removed < excess) {
                val m = iter.next()
                if (m.role != "system") {
                    iter.remove()
                    removed++
                }
            }
        }
    }

    @Synchronized
    fun getAll(): List<Message> = messages.toList()

    @Synchronized
    fun clear() = messages.clear()

    /** Build the JSON array string for the API request body. */
    @Synchronized
    fun toJsonArray(): String {
        val sb = StringBuilder("[")
        messages.forEachIndexed { index, msg ->
            if (index > 0) sb.append(",")
            val escapedContent = msg.content
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
            sb.append("{\"role\":\"${msg.role}\",\"content\":\"$escapedContent\"}")
        }
        sb.append("]")
        return sb.toString()
    }
}
