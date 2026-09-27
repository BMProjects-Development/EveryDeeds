package ru.benos.everydeeds.deed

import net.minecraft.resources.Identifier

/**
 * One raw action counter of the ledger: [action] done with [targetId] (an object of [category]),
 * independent of any deed definition. Used as a map key on the server, in packets and on the client,
 * so counting an action never builds strings.
 */
data class LedgerKey(val category: DeedCategory, val action: DeedAction, val targetId: Identifier) {
    /** Storage form, `blocks/broken/minecraft:stone`; kept stable so saved worlds stay readable. */
    fun serialize(): String = "${category.serializedName}/${action.serializedName}/$targetId"

    companion object {
        /** Inverse of [serialize]; null for keys of categories or actions that no longer exist. */
        fun parse(text: String): LedgerKey? {
            val parts = text.split('/', limit = 3)
            if (parts.size != 3) return null
            val category = DeedCategory.entries.firstOrNull { category -> category.serializedName == parts[0] } ?: return null
            val action = DeedAction.entries.firstOrNull { action -> action.serializedName == parts[1] } ?: return null
            val target = Identifier.tryParse(parts[2]) ?: return null
            return LedgerKey(category, action, target)
        }
    }
}
