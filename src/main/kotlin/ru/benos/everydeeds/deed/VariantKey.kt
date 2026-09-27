package ru.benos.everydeeds.deed

/**
 * Compact, stable 64-bit keys for variants (an enchantment, a block state, a dye colour...).
 *
 * Keys are derived from stable textual identities (registry ids, property names) rather than raw
 * registry ids, so they survive restarts and modpack changes. Collisions are astronomically
 * unlikely for the handful of thousands of variants a player can realistically produce.
 */
object VariantKey {
    private const val FNV_OFFSET_BASIS: Long = -0x340d631b7bdddcdbL
    private const val FNV_PRIME: Long = 0x100000001b3L

    /** FNV-1a over the UTF-16 chars of [text]. */
    fun of(text: String): Long {
        var hash = FNV_OFFSET_BASIS
        for (char in text) {
            hash = hash xor (char.code.toLong() and 0xFF)
            hash *= FNV_PRIME
            hash = hash xor ((char.code.toLong() ushr 8) and 0xFF)
            hash *= FNV_PRIME
        }
        return hash
    }

    /** Numeric variants (durability, RGB colour): an odd-multiplier bijection, so collision-free within a kind. */
    fun of(kind: String, value: Long): Long =
        of(kind) xor (value * FNV_PRIME)
}
