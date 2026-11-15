package dev.lusalhi.lumynoid.core

import java.security.SecureRandom

/** Source of entity ids; every entity id is a UUIDv7 string. */
fun interface IdGenerator {
    fun next(): String
}

/** Source of epoch-millis timestamps. */
fun interface Clock {
    fun nowMillis(): Long
}

object UuidV7 : IdGenerator {
    private val random = SecureRandom()

    override fun next(): String {
        val millis = System.currentTimeMillis()
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        for (i in 0 until 6) bytes[i] = (millis ushr (8 * (5 - i))).toByte()
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
    }
}

object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
