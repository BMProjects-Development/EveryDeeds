package ru.benos.everydeeds.network

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import ru.benos.everydeeds.EveryDeeds
import ru.benos.everydeeds.deed.DeedAction
import ru.benos.everydeeds.deed.DeedCategory
import ru.benos.everydeeds.deed.LedgerKey
import java.util.Optional

/*
 * Wire format notes: targets are identified by their id. Biomes, dimensions and structures live in
 * data-driven registries the client may not even have, so raw registry ids cannot be used; the full
 * sync writes every distinct id once into a string table and refers to it by index. Deeds are
 * referenced by their index in the last full sync. A snapshot of tens of thousands of deeds stays
 * within a few hundred kilobytes.
 */

/** Static description of one definition, shared by all deeds expanded from it. */
data class DeedTemplateView(
    val definitionId: Identifier,
    val category: DeedCategory,
    val action: DeedAction,
    /** Variant type for unique goals, empty for count goals. */
    val variantType: String,
    val hasPredicate: Boolean,
    val title: Optional<Component>,
    /** Milestone: a single deed for the whole category (its target is a placeholder id). */
    val aggregate: Boolean = false,
    val icon: Optional<Identifier> = Optional.empty()
)

data class DeedInstanceView(val templateIndex: Int, val targetId: Identifier, val required: Long)

data class DeedProgressView(val instanceIndex: Int, val value: Long, val completed: Boolean)

/** Raw action counter ("broke minecraft:stone 37 times"), independent of deed definitions. */
data class LedgerView(val key: LedgerKey, val count: Long)

/** S2C: complete snapshot of the active set and the player's progress. Sent on join, reload and request. */
data class DeedSyncPayload(
    val set: String,
    /** Every loaded set, so the client can offer a difficulty switch. */
    val availableSets: List<String>,
    /** Whether this player may switch the world's set (same permission as `/everydeeds set`). */
    val canChangeSet: Boolean,
    val templates: List<DeedTemplateView>,
    val instances: List<DeedInstanceView>,
    val progress: List<DeedProgressView>,
    val ledger: List<LedgerView>
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<DeedSyncPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<DeedSyncPayload> = payloadType("deed_sync")

        /** Upper bound for the split large-payload channel. */
        const val MAX_SIZE: Int = 32 * 1024 * 1024

        val CODEC: StreamCodec<RegistryFriendlyByteBuf, DeedSyncPayload> = StreamCodec.of(
            { buf, payload ->
                buf.writeUtf(payload.set)
                buf.writeList(payload.availableSets) { set -> buf.writeUtf(set) }
                buf.writeBoolean(payload.canChangeSet)
                buf.writeList(payload.templates) { template ->
                    buf.writeIdentifier(template.definitionId)
                    buf.writeVarInt(template.category.ordinal)
                    buf.writeVarInt(template.action.ordinal)
                    buf.writeUtf(template.variantType)
                    buf.writeBoolean(template.hasPredicate)
                    ComponentSerialization.OPTIONAL_STREAM_CODEC.encode(buf, template.title)
                    buf.writeBoolean(template.aggregate)
                    buf.writeOptional(template.icon) { out, id -> out.writeIdentifier(id) }
                }
                val targets = TargetTable()
                payload.instances.forEach { instance -> targets.ref(instance.targetId) }
                payload.ledger.forEach { entry -> targets.ref(entry.key.targetId) }
                buf.writeList(targets.ids) { id -> buf.writeIdentifier(id) }

                buf.writeList(payload.instances) { instance ->
                    buf.writeVarInt(instance.templateIndex)
                    buf.writeVarInt(targets.ref(instance.targetId))
                    buf.writeVarLong(instance.required)
                }
                writeProgress(buf, payload.progress)
                writeLedger(buf, payload.ledger) { id -> buf.writeVarInt(targets.ref(id)) }
            },
            { buf ->
                val set = buf.readUtf()
                val availableSets = buf.readList { buf.readUtf() }
                val canChangeSet = buf.readBoolean()
                val templates = buf.readList {
                        DeedTemplateView(
                            definitionId = buf.readIdentifier(),
                            category = DeedCategory.entries[buf.readVarInt()],
                            action = DeedAction.entries[buf.readVarInt()],
                            variantType = buf.readUtf(),
                            hasPredicate = buf.readBoolean(),
                            title = ComponentSerialization.OPTIONAL_STREAM_CODEC.decode(buf),
                            aggregate = buf.readBoolean(),
                            icon = buf.readOptional { input -> input.readIdentifier() }
                        )
                    }
                val targets = buf.readList { buf.readIdentifier() }
                DeedSyncPayload(
                    set = set,
                    availableSets = availableSets,
                    canChangeSet = canChangeSet,
                    templates = templates,
                    instances = buf.readList { DeedInstanceView(buf.readVarInt(), targets[buf.readVarInt()], buf.readVarLong()) },
                    progress = readProgress(buf),
                    ledger = readLedger(buf) { targets[buf.readVarInt()] }
                )
            }
        )
    }
}

/** S2C: batched progress changes since the last flush. */
data class DeedProgressPayload(
    val progress: List<DeedProgressView>,
    val ledger: List<LedgerView>
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<DeedProgressPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<DeedProgressPayload> = payloadType("deed_progress")

        val CODEC: StreamCodec<RegistryFriendlyByteBuf, DeedProgressPayload> = StreamCodec.of(
            { buf, payload ->
                writeProgress(buf, payload.progress)
                writeLedger(buf, payload.ledger) { id -> buf.writeIdentifier(id) }
            },
            { buf -> DeedProgressPayload(readProgress(buf), readLedger(buf) { buf.readIdentifier() }) }
        )
    }
}

/** S2C: deeds completed since the last flush; drives the unlock toast. Separate from progress on purpose. */
data class DeedUnlockedPayload(val instanceIndices: List<Int>) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<DeedUnlockedPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<DeedUnlockedPayload> = payloadType("deed_unlocked")

        val CODEC: StreamCodec<RegistryFriendlyByteBuf, DeedUnlockedPayload> = StreamCodec.of(
            { buf, payload -> buf.writeList(payload.instanceIndices) { index -> buf.writeVarInt(index) } },
            { buf -> DeedUnlockedPayload(buf.readList { buf.readVarInt() }) }
        )
    }
}

/** C2S: switch the world's deed set. The server re-checks the permission; unauthorised requests are ignored. */
data class ChangeDeedSetPayload(val set: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ChangeDeedSetPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<ChangeDeedSetPayload> = payloadType("change_deed_set")

        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ChangeDeedSetPayload> = StreamCodec.of(
            { buf, payload -> buf.writeUtf(payload.set, 64) },
            { buf -> ChangeDeedSetPayload(buf.readUtf(64)) }
        )
    }
}

/** C2S: which variants of this deed the player has collected (asked when the details window lists missing ones). */
data class RequestDeedVariantsPayload(val instanceIndex: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<RequestDeedVariantsPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<RequestDeedVariantsPayload> = payloadType("request_deed_variants")

        val CODEC: StreamCodec<RegistryFriendlyByteBuf, RequestDeedVariantsPayload> = StreamCodec.of(
            { buf, payload -> buf.writeVarInt(payload.instanceIndex) },
            { buf -> RequestDeedVariantsPayload(buf.readVarInt()) }
        )
    }
}

/** S2C: the collected variant keys of one deed, in reply to [RequestDeedVariantsPayload]. */
data class DeedVariantsPayload(val instanceIndex: Int, val keys: LongArray) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<DeedVariantsPayload> = TYPE

    override fun equals(other: Any?): Boolean =
        other is DeedVariantsPayload && other.instanceIndex == instanceIndex && other.keys.contentEquals(keys)

    override fun hashCode(): Int = 31 * instanceIndex + keys.contentHashCode()

    companion object {
        val TYPE: CustomPacketPayload.Type<DeedVariantsPayload> = payloadType("deed_variants")

        val CODEC: StreamCodec<RegistryFriendlyByteBuf, DeedVariantsPayload> = StreamCodec.of(
            { buf, payload ->
                buf.writeVarInt(payload.instanceIndex)
                buf.writeVarInt(payload.keys.size)
                payload.keys.forEach { key -> buf.writeLong(key) }
            },
            { buf -> DeedVariantsPayload(buf.readVarInt(), LongArray(buf.readVarInt()) { buf.readLong() }) }
        )
    }
}

/** C2S: ask the server for a full [DeedSyncPayload]. */
data object RequestDeedSyncPayload : CustomPacketPayload {
    val TYPE: CustomPacketPayload.Type<RequestDeedSyncPayload> = payloadType("request_deed_sync")

    val CODEC: StreamCodec<RegistryFriendlyByteBuf, RequestDeedSyncPayload> = StreamCodec.unit(RequestDeedSyncPayload)

    override fun type(): CustomPacketPayload.Type<RequestDeedSyncPayload> = TYPE
}

private fun <T : CustomPacketPayload> payloadType(path: String): CustomPacketPayload.Type<T> =
    CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(EveryDeeds.MOD_ID, path))

private inline fun <T> RegistryFriendlyByteBuf.writeList(values: List<T>, writeValue: (T) -> Unit) {
    writeVarInt(values.size)
    values.forEach(writeValue)
}

private inline fun <T> RegistryFriendlyByteBuf.readList(readValue: () -> T): List<T> {
    val size = readVarInt()
    return List(size) { readValue() }
}

private fun writeProgress(buf: RegistryFriendlyByteBuf, progress: List<DeedProgressView>) {
    buf.writeList(progress) { entry ->
        buf.writeVarInt(entry.instanceIndex)
        buf.writeVarLong(entry.value)
        buf.writeBoolean(entry.completed)
    }
}

private fun readProgress(buf: RegistryFriendlyByteBuf): List<DeedProgressView> =
    buf.readList { DeedProgressView(buf.readVarInt(), buf.readVarLong(), buf.readBoolean()) }

/** Distinct target ids of one full sync, each written once and then referenced by index. */
private class TargetTable {
    val ids = ArrayList<Identifier>()
    private val indices = HashMap<Identifier, Int>()

    fun ref(id: Identifier): Int = indices.getOrPut(id) { ids.size.also { ids += id } }
}

private fun writeLedger(buf: RegistryFriendlyByteBuf, ledger: List<LedgerView>, writeTarget: (Identifier) -> Unit) {
    buf.writeList(ledger) { entry ->
        buf.writeVarInt(entry.key.category.ordinal)
        buf.writeVarInt(entry.key.action.ordinal)
        writeTarget(entry.key.targetId)
        buf.writeVarLong(entry.count)
    }
}

private fun readLedger(buf: RegistryFriendlyByteBuf, readTarget: () -> Identifier): List<LedgerView> =
    buf.readList {
        LedgerView(LedgerKey(DeedCategory.entries[buf.readVarInt()], DeedAction.entries[buf.readVarInt()], readTarget()), buf.readVarLong())
    }
