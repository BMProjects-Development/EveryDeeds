package ru.benos.everydeeds.gametest

import com.mojang.blaze3d.platform.InputConstants
import it.unimi.dsi.fastutil.ints.IntList
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.advancements.triggers.CriteriaTriggers
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import net.minecraft.world.InteractionHand
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntitySpawnRequest
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.animal.pig.Pig
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.projectile.FireworkRocketEntity
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.inventory.BrewingStandMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.FireworkExplosion
import net.minecraft.world.item.component.Fireworks
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.item.enchantment.ItemEnchantments
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import ru.benos.everydeeds.client.EveryDeedsClient
import ru.benos.everydeeds.client.deed.ClientDeedSnapshot
import ru.benos.everydeeds.client.deed.SnapshotAchievementUiDataSource
import ru.benos.everydeeds.client.deed.TargetEntries
import ru.benos.everydeeds.client.gui.DeedToast
import ru.benos.everydeeds.client.gui.MissingVariants
import ru.benos.everydeeds.client.gui.TabloScreen
import ru.benos.everydeeds.client.gui.displayName
import ru.benos.everydeeds.data.DeedDefinitions
import ru.benos.everydeeds.deed.DeedAction
import ru.benos.everydeeds.deed.DeedCategory
import ru.benos.everydeeds.deed.DeedDefinition
import ru.benos.everydeeds.deed.DeedGoal
import ru.benos.everydeeds.deed.DeedVariant
import ru.benos.everydeeds.deed.TargetSelector
import ru.benos.everydeeds.deed.VariantOption
import ru.benos.everydeeds.deed.VariantOptions
import ru.benos.everydeeds.network.ChangeDeedSetPayload
import ru.benos.everydeeds.platform.DeedPlatform
import ru.benos.everydeeds.tracking.Celebration
import ru.benos.everydeeds.tracking.DeedTracker
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional

/**
 * End-to-end check of the server-authoritative pipeline in a real singleplayer world:
 * event source -> DeedTracker -> batched sync -> client snapshot -> Tablo screen, then set switching
 * and persistence across a world reload.
 */
class DeedTrackingClientTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        val world = context.worldBuilder().create()
        val save = world.worldSave

        world.use { singleplayer ->
            singleplayer.connection.waitForChunksRender()
            singleplayer.server.runCommand("gamemode survival @a")
            // Previews must work on Peaceful too, where hostile mobs cannot normally be created.
            singleplayer.server.runCommand("difficulty peaceful")
            context.waitFor({ ClientDeedSnapshot.isSynced }, 200)

            check(ClientDeedSnapshot.set == "short") { "Expected the default 'short' set, got '${ClientDeedSnapshot.set}'" }
            check(ClientDeedSnapshot.instances.isNotEmpty()) { "Expected deeds in the synced snapshot" }
            verifySpawnVisit(context, singleplayer)
            verifyNoImpossibleDeeds(singleplayer)
            verifyBuiltinPacks(context, singleplayer)
            verifyEntityPreviews(context)
            context.setScreen { PreviewGalleryScreen() }
            context.waitTicks(40)
            context.takeScreenshot("gallery_01")
            context.waitTicks(15)
            context.takeScreenshot("gallery_02")
            // Fabulous graphics (improved transparency) draws some things with other pipelines: boats once crashed the game here.
            context.onClient { client -> client.options.improvedTransparency().set(true) }
            context.waitTicks(10)
            context.takeScreenshot("gallery_03_fabulous")
            context.onClient { client -> client.options.improvedTransparency().set(false) }
            context.setScreen { null }
            singleplayer.server.runCommand("difficulty normal")
            // Spawn invulnerability must run out before the damage test.
            context.waitTicks(80)

            performActions(context, singleplayer)
            walk(context, singleplayer)
            ride(context, singleplayer)
            swim(context, singleplayer)
            context.waitTicks(30)

            verifyLedger(context, singleplayer)
            verifyClientSnapshot(context)
            context.takeScreenshot("deeds_01_toast")

            verifyScreen(context)
            verifyHeavySets(context, singleplayer)
            verifySetSwitchAndVariants(context, singleplayer)
            verifyFinale(context, singleplayer)
        }

        // Persistence: progress and the active set survive a world reload.
        save.open().use { reopened ->
            reopened.connection.waitForChunksRender()
            context.waitFor({ ClientDeedSnapshot.isSynced && ClientDeedSnapshot.set == "extended" }, 200)
            val mined = reopened.server.computeOnServer<Long, RuntimeException> { server ->
                val data = DeedPlatform.current.playerData(player(server))
                data.ledgerCount(DeedCategory.BLOCKS, DeedAction.BROKEN, id("stone"))
            }
            check(mined >= 1L) { "Expected the mined-stone counter to survive a reload, got $mined" }
        }
    }

    /** The biome and dimension the player spawns in count right away, on the server and on the client. */
    private fun verifySpawnVisit(context: ClientGameTestContext, singleplayer: TestSingleplayerContext) {
        context.waitTicks(40)
        val server = singleplayer.server.computeOnServer<Pair<Long, Int>, RuntimeException> { server ->
            val data = DeedPlatform.current.playerData(player(server))
            val biomes = data.ledger.keys.count { key -> key.category == DeedCategory.BIOMES && key.action == DeedAction.VISITED }
            data.ledgerCount(DeedCategory.DIMENSIONS, DeedAction.VISITED, id("overworld")) to biomes
        }
        val client = context.onClient { _ ->
            val biomes = ClientDeedSnapshot.targets(DeedCategory.BIOMES)
                .count { biome -> ClientDeedSnapshot.ledgerCount(DeedCategory.BIOMES, DeedAction.VISITED, biome) > 0 }
            ClientDeedSnapshot.ledgerCount(DeedCategory.DIMENSIONS, DeedAction.VISITED, id("overworld")) to biomes
        }
        check(server.first > 0 && server.second > 0) { "Spawn visit not recorded on the server: $server" }
        check(client.first > 0 && client.second > 0) { "Spawn visit not synced to the client: $client (server: $server)" }
    }

    /** Deeds nobody can complete in survival must never be indexed. */
    private fun verifyNoImpossibleDeeds(singleplayer: TestSingleplayerContext) {
        val forbidden = mapOf(
            DeedAction.SEEN to listOf("air", "piston_head", "moving_piston", "barrier", "structure_void", "light", "bubble_column"),
            DeedAction.BROKEN to listOf("piston_head", "barrier", "bedrock", "water", "lava", "fire", "end_portal", "nether_portal", "light"),
            DeedAction.PLACED to listOf("piston_head", "moving_piston", "water", "fire", "bedrock", "spawner", "end_portal", "barrier",
                "water_cauldron", "lava_cauldron", "powder_snow_cauldron", "big_dripleaf_stem", "kelp_plant"),
            DeedAction.OBTAINED to listOf("pig_spawn_egg", "barrier", "command_block", "debug_stick", "bedrock", "luck", "unluck", "health_boost"),
            DeedAction.USED to listOf("stone", "dirt", "oak_planks", "barrier", "command_block", "stick", "diamond"),
            DeedAction.CRAFTED to listOf("wall_torch", "bedrock", "diamond_ore", "barrier"),
            DeedAction.GROWN to listOf("stone", "grass_block"),
            DeedAction.TRADED to listOf("pig", "zombie", "player"),
            // Registered but generated nowhere.
            DeedAction.VISITED to listOf("the_void"),
            DeedAction.ENCHANTED to listOf("book", "elytra", "stick"),
            DeedAction.LOOTED to listOf("nether_fossil", "monument", "swamp_hut"),
            DeedAction.KILLED to listOf("player", "giant"),
            // Only the plain minecart takes a passenger.
            DeedAction.RIDDEN to listOf("chest_minecart", "furnace_minecart", "hopper_minecart", "tnt_minecart")
        )
        // And the ones that must be there.
        val required = listOf(
            "used lever", "used crafting_table", "used chest", "used bow", "used diamond_sword", "used apple",
            "crafted oak_planks", "crafted stick", "eaten bread",
            "tamed wolf", "bred cow", "ridden pig", "ridden oak_boat", "ridden minecart", "fished cod", "brewed potion", "enchanted diamond_sword",
            "enchanted enchanted_book", "obtained speed", "obtained darkness", "damaged_by cactus", "damaged_by magma_block", "damaged_by anvil", "grown wheat", "grown oak_sapling", "traded villager", "traded wandering_trader",
            "visited overworld", "visited the_nether", "visited the_end", "visited plains",
            "seen village_plains", "visited village_plains", "looted village_plains", "seen fortress", "looted end_city"
        )
        val found = singleplayer.server.computeOnServer<List<String>, RuntimeException> { _ ->
            DeedTracker.index.instances
                .filter { instance -> forbidden[instance.definition.action]?.contains(instance.targetId.path) == true }
                .map { instance -> "${instance.definition.action.serializedName} ${instance.targetId}" }
        }
        check(found.isEmpty()) { "Impossible deeds were indexed: $found" }
        val indexed = singleplayer.server.computeOnServer<Set<String>, RuntimeException> { _ ->
            DeedTracker.index.instances.map { instance -> "${instance.definition.action.serializedName} ${instance.targetId.path}" }.toSet()
        }
        val absent = required.filter { key -> key !in indexed }
        check(absent.isEmpty()) { "Expected deeds are not indexed: $absent" }

        // Full dump for manual review: one line per rule with all of its targets.
        singleplayer.server.runOnServer<RuntimeException> { _ ->
            val dump = DeedTracker.index.instances
                .groupBy { instance -> instance.definitionId.toString() }
                .entries.joinToString("\n\n") { (definition, instances) ->
                    "$definition (${instances.size}):\n" + instances.joinToString(" ") { instance -> instance.targetId.path }
                }
            Files.writeString(Path.of("deed_index_dump.txt"), dump)
        }
    }

    /** The built-in "More milestones" data pack is on by default and adds its milestones to the active set. */
    private fun verifyBuiltinPacks(context: ClientGameTestContext, singleplayer: TestSingleplayerContext) {
        val (enabled, indexed) = singleplayer.server.computeOnServer<Pair<Boolean, Boolean>, RuntimeException> { server ->
            val enabled = server.packRepository.selectedIds.any { id -> id.contains("more_milestones") }
            val indexed = DeedTracker.index.instances.any { instance -> instance.definitionId.path == "short/milestones/grown_50" }
            enabled to indexed
        }
        check(enabled) { "The 'More milestones' pack must be enabled by default" }
        check(indexed) { "The milestones of the 'More milestones' pack must be in the index" }

        // Milestones of both packs mix into one group, each action's tiers ordered by amount.
        val firstTiers = context.onClient { _ ->
            SnapshotAchievementUiDataSource().groups(DeedCategory.BLOCKS).first().entries.take(4).map { entry -> entry.name.string }
        }
        check(firstTiers == listOf("100", "1 000", "5 000", "10 000")) { "Milestones must be ordered by amount: $firstTiers" }
    }

    /** Every mob with deeds must be renderable as a live preview (no silent fallback to its spawn egg). */
    private fun verifyEntityPreviews(context: ClientGameTestContext) {
        val failures = context.onClient { client ->
            val level = client.level!!
            ClientDeedSnapshot.targets(DeedCategory.ENTITIES).mapIndexedNotNull { index, targetId ->
                val type = BuiltInRegistries.ENTITY_TYPE.getValue(targetId)
                val problem = runCatching {
                    val entity = type.create(level, EntitySpawnRequest(EntitySpawnReason.LOAD, true))
                        ?: return@runCatching "create() returned null"
                    entity.id = -1000 - index
                    client.entityRenderDispatcher.getRenderer(entity).createRenderState(entity, 1f)
                    null
                }.getOrElse { failure -> "${failure.javaClass.simpleName}: ${failure.message}" }
                problem?.let { "${BuiltInRegistries.ENTITY_TYPE.getKey(type)} -> $it" }
            }
        }
        check(failures.isEmpty()) { "Entity previews failing:\n" + failures.joinToString("\n") }
    }

    private fun performActions(context: ClientGameTestContext, singleplayer: TestSingleplayerContext) {
        // Seen (block): put a gold block in front of the player and look at it.
        val goldPos = singleplayer.server.computeOnServer<BlockPos, RuntimeException> { server ->
            val player = player(server)
            val pos = player.blockPosition().relative(Direction.NORTH, 3).above()
            player.level().setBlockAndUpdate(pos, Blocks.GOLD_BLOCK.defaultBlockState())
            pos
        }
        context.input.lookAt(goldPos)
        context.waitTicks(10)

        // Seen (entity): a pig in plain view, killed right after.
        val pigPos = singleplayer.server.computeOnServer<BlockPos, RuntimeException> { server ->
            val player = player(server)
            val pos = player.blockPosition().relative(Direction.SOUTH, 4)
            player.level().setBlockAndUpdate(goldPos, Blocks.AIR.defaultBlockState())
            EntityTypes.PIG.spawn(player.level(), pos, EntitySpawnReason.COMMAND)
            pos
        }
        context.input.lookAt(pigPos)
        context.waitTicks(15)

        singleplayer.server.runOnServer<RuntimeException> { server ->
            val player = player(server)
            val level = player.level()

            // Broken.
            val stonePos = player.blockPosition().relative(Direction.EAST, 2)
            level.setBlockAndUpdate(stonePos, Blocks.STONE.defaultBlockState())
            check(player.gameMode.destroyBlock(stonePos)) { "Could not break the test stone" }

            // Placed.
            val placeOn = player.blockPosition().relative(Direction.WEST, 2).below()
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.OAK_PLANKS, 4))
            val hit = BlockHitResult(Vec3.atCenterOf(placeOn).add(0.0, 0.5, 0.0), Direction.UP, placeOn, false)
            player.getItemInHand(InteractionHand.MAIN_HAND).useOn(UseOnContext(player, InteractionHand.MAIN_HAND, hit))

            // Picked up.
            val drop = ItemEntity(level, player.x, player.y, player.z, ItemStack(Items.APPLE, 3))
            drop.setNoPickUpDelay()
            level.addFreshEntity(drop)
            drop.playerTouch(player)

            // Obtained by being in the inventory at all (counted once, on the next inventory sync).
            player.inventory.add(ItemStack(Items.EMERALD, 5))

            // Crafted: the item, and the block for block items.
            ItemStack(Items.STICK, 4).onCraftedBy(player, 4)
            ItemStack(Items.OAK_PLANKS, 4).onCraftedBy(player, 4)

            // Used (block): an empty hand on a lever.
            val leverPos = player.blockPosition().relative(Direction.SOUTH, 2)
            level.setBlockAndUpdate(leverPos.below(), Blocks.STONE.defaultBlockState())
            level.setBlockAndUpdate(leverPos, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR))
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
            val leverHit = BlockHitResult(Vec3.atCenterOf(leverPos), Direction.UP, leverPos, false)
            player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, leverHit)

            // Used (item): the vanilla statistic the game awards on every use.
            player.awardStat(Stats.ITEM_USED.get(Items.BOW))

            // Grown: a mature crop harvested, and bone meal on a sapling.
            val wheatPos = player.blockPosition().relative(Direction.EAST, 3)
            level.setBlockAndUpdate(wheatPos.below(), Blocks.FARMLAND.defaultBlockState())
            level.setBlockAndUpdate(wheatPos, (Blocks.WHEAT as CropBlock).getStateForAge(7))
            check(player.gameMode.destroyBlock(wheatPos)) { "Could not harvest the test wheat" }
            val saplingPos = player.blockPosition().relative(Direction.WEST, 3)
            level.setBlockAndUpdate(saplingPos.below(), Blocks.GRASS_BLOCK.defaultBlockState())
            level.setBlockAndUpdate(saplingPos, Blocks.OAK_SAPLING.defaultBlockState())
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.BONE_MEAL, 4))
            val saplingHit = BlockHitResult(Vec3.atCenterOf(saplingPos), Direction.UP, saplingPos, false)
            player.getItemInHand(InteractionHand.MAIN_HAND).useOn(UseOnContext(player, InteractionHand.MAIN_HAND, saplingHit))
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)

            // Eaten: the consumable component finishing on the player.
            val bread = ItemStack(Items.BREAD)
            bread.get(DataComponents.CONSUMABLE)!!.onConsume(level, player, bread)

            // Tamed, bred, ridden: the real game paths (taming, the breeding trigger, mounting).
            val wolf = EntityTypes.WOLF.spawn(level, player.blockPosition().relative(Direction.SOUTH, 3), EntitySpawnReason.COMMAND)!!
            wolf.tame(player)
            wolf.discard()
            val cow = EntityTypes.COW.spawn(level, player.blockPosition().relative(Direction.SOUTH, 3), EntitySpawnReason.COMMAND)!!
            val calf = EntityTypes.COW.spawn(level, player.blockPosition().relative(Direction.SOUTH, 3), EntitySpawnReason.COMMAND)!!
            CriteriaTriggers.BRED_ANIMALS.trigger(player, cow, cow, calf)
            cow.discard()
            calf.discard()

            // Fished, enchanted, brewed.
            val hook = FishingHook(player, level, 0, 0)
            CriteriaTriggers.FISHING_ROD_HOOKED.trigger(player, ItemStack(Items.FISHING_ROD), hook, listOf(ItemStack(Items.COD)))
            hook.discard()
            CriteriaTriggers.ENCHANTED_ITEM.trigger(player, ItemStack(Items.DIAMOND_SWORD), 30)
            val brewing = BrewingStandMenu(0, player.inventory)
            brewing.getSlot(0).onTake(player, ItemStack(Items.POTION))

            // Effect.
            player.addEffect(MobEffectInstance(MobEffects.SPEED, 200))

            // Traded.
            val trader = EntityTypes.WANDERING_TRADER.spawn(level, player.blockPosition().relative(Direction.NORTH, 2), EntitySpawnReason.COMMAND)!!
            trader.setTradingPlayer(player)
            trader.notifyTrade(trader.offers.first())
            trader.setTradingPlayer(null)
            trader.discard()

            // Killed.
            val pig = level.getEntitiesOfClass(Pig::class.java, player.boundingBox.inflate(16.0)).first()
            pig.hurtServer(level, level.damageSources().playerAttack(player), 1000f)

            // Damaged by.
            val zombie = EntityTypes.ZOMBIE.spawn(level, player.blockPosition().relative(Direction.NORTH, 6), EntitySpawnReason.COMMAND)!!
            player.hurtServer(level, level.damageSources().mobAttack(zombie), 2f)
            zombie.discard()

            // Damaged by blocks: magma underfoot, and a falling anvil.
            player.damageCooldownTime = 0
            level.setBlockAndUpdate(player.blockPosition().below(), Blocks.MAGMA_BLOCK.defaultBlockState())
            player.hurtServer(level, level.damageSources().hotFloor(), 1f)
            player.damageCooldownTime = 0
            val anvil = FallingBlockEntity.fall(level, player.blockPosition().above(3), Blocks.ANVIL.defaultBlockState())
            player.hurtServer(level, level.damageSources().anvil(anvil), 1f)
            anvil.discard()
        }
    }

    /** Travel is real movement: 40 ticks of half a block each, so about 20 blocks in the current biome. */
    private fun walk(context: ClientGameTestContext, singleplayer: TestSingleplayerContext) {
        repeat(40) {
            singleplayer.server.runOnServer<RuntimeException> { server ->
                val player = player(server)
                player.teleportTo(player.x + 0.5, player.y, player.z)
            }
            context.waitTicks(1)
        }
    }

    /**
     * Mounting counts when the player is actually sitting on the entity for a tick; the distance ridden
     * is the pig's way under the player: 40 ticks of half a block each, about 20 blocks.
     */
    private fun ride(context: ClientGameTestContext, singleplayer: TestSingleplayerContext) {
        singleplayer.server.runOnServer<RuntimeException> { server ->
            val player = player(server)
            val pig = EntityTypes.PIG.spawn(player.level(), player.blockPosition(), EntitySpawnReason.COMMAND)!!
            player.startRiding(pig, true, true)
        }
        repeat(40) {
            singleplayer.server.runOnServer<RuntimeException> { server ->
                val pig = player(server).vehicle ?: return@runOnServer
                pig.teleportTo(pig.x, pig.y, pig.z - 0.5)
            }
            context.waitTicks(1)
        }
        singleplayer.server.runOnServer<RuntimeException> { server ->
            val player = player(server)
            val pig = player.vehicle
            player.stopRiding()
            pig?.discard()
        }
    }

    /** Swimming is moving in a liquid by oneself: 40 ticks of half a block each along a line of water, about 20 blocks. */
    private fun swim(context: ClientGameTestContext, singleplayer: TestSingleplayerContext) {
        singleplayer.server.runOnServer<RuntimeException> { server ->
            val player = player(server)
            val start = player.blockPosition()
            for (step in 0..24) {
                player.level().setBlockAndUpdate(start.relative(Direction.SOUTH, step), Blocks.WATER.defaultBlockState())
            }
        }
        repeat(40) {
            singleplayer.server.runOnServer<RuntimeException> { server ->
                val player = player(server)
                player.teleportTo(player.x, player.y, player.z + 0.5)
            }
            context.waitTicks(1)
        }
    }

    private fun verifyLedger(context: ClientGameTestContext, singleplayer: TestSingleplayerContext) {
        val counts = singleplayer.server.computeOnServer<Map<String, Long>, RuntimeException> { server ->
            val data = DeedPlatform.current.playerData(player(server))
            mapOf(
                "seen gold_block" to data.ledgerCount(DeedCategory.BLOCKS, DeedAction.SEEN, id("gold_block")),
                "mined stone" to data.ledgerCount(DeedCategory.BLOCKS, DeedAction.BROKEN, id("stone")),
                "placed oak_planks" to data.ledgerCount(DeedCategory.BLOCKS, DeedAction.PLACED, id("oak_planks")),
                "obtained apple" to data.ledgerCount(DeedCategory.ITEMS, DeedAction.OBTAINED, id("apple")),
                "obtained emerald" to data.ledgerCount(DeedCategory.ITEMS, DeedAction.OBTAINED, id("emerald")),
                "obtained stick" to data.ledgerCount(DeedCategory.ITEMS, DeedAction.OBTAINED, id("stick")),
                "crafted stick" to data.ledgerCount(DeedCategory.ITEMS, DeedAction.CRAFTED, id("stick")),
                "crafted oak_planks block" to data.ledgerCount(DeedCategory.BLOCKS, DeedAction.CRAFTED, id("oak_planks")),
                "used lever" to data.ledgerCount(DeedCategory.BLOCKS, DeedAction.USED, id("lever")),
                "tamed wolf" to data.ledgerCount(DeedCategory.ENTITIES, DeedAction.TAMED, id("wolf")),
                "bred cow" to data.ledgerCount(DeedCategory.ENTITIES, DeedAction.BRED, id("cow")),
                "ridden pig" to data.ledgerCount(DeedCategory.ENTITIES, DeedAction.RIDDEN, id("pig")),
                "fished cod" to data.ledgerCount(DeedCategory.ITEMS, DeedAction.FISHED, id("cod")),
                "enchanted diamond_sword" to data.ledgerCount(DeedCategory.ITEMS, DeedAction.ENCHANTED, id("diamond_sword")),
                "brewed potion" to data.ledgerCount(DeedCategory.ITEMS, DeedAction.BREWED, id("potion")),
                "obtained speed" to data.ledgerCount(DeedCategory.EFFECTS, DeedAction.OBTAINED, id("speed")),
                "milestone broken" to DeedTracker.index.instances
                    .filter { instance -> instance.definition.aggregate && instance.definition.action == DeedAction.BROKEN }
                    .maxOf { instance -> data.progress[instance.key]?.count ?: 0L },
                "eaten bread" to data.ledgerCount(DeedCategory.ITEMS, DeedAction.EATEN, id("bread")),
                "used bow" to data.ledgerCount(DeedCategory.ITEMS, DeedAction.USED, id("bow")),
                "grown wheat" to data.ledgerCount(DeedCategory.BLOCKS, DeedAction.GROWN, id("wheat")),
                "grown oak_sapling" to data.ledgerCount(DeedCategory.BLOCKS, DeedAction.GROWN, id("oak_sapling")),
                "traded wandering_trader" to data.ledgerCount(DeedCategory.ENTITIES, DeedAction.TRADED, id("wandering_trader")),
                "visited overworld" to data.ledgerCount(DeedCategory.DIMENSIONS, DeedAction.VISITED, id("overworld")),
                "time in overworld" to data.ledgerCount(DeedCategory.DIMENSIONS, DeedAction.TIME_SPENT, id("overworld")),
                "visited biome" to data.ledger.keys.count { key ->
                    key.category == DeedCategory.BIOMES && key.action == DeedAction.VISITED
                }.toLong(),
                "traveled in biomes" to data.ledger.filterKeys { key ->
                    key.category == DeedCategory.BIOMES && key.action == DeedAction.TRAVELED
                }.values.sum(),
                "swum in water" to data.ledgerCount(DeedCategory.BLOCKS, DeedAction.TRAVELED, id("water")),
                "rode pig" to data.ledgerCount(DeedCategory.ENTITIES, DeedAction.TRAVELED, id("pig")),
                "seen pig" to data.ledgerCount(DeedCategory.ENTITIES, DeedAction.SEEN, id("pig")),
                "killed pig" to data.ledgerCount(DeedCategory.ENTITIES, DeedAction.KILLED, id("pig")),
                "damaged_by zombie" to data.ledgerCount(DeedCategory.ENTITIES, DeedAction.DAMAGED_BY, id("zombie")),
                "damaged_by magma_block" to data.ledgerCount(DeedCategory.BLOCKS, DeedAction.DAMAGED_BY, id("magma_block")),
                "damaged_by anvil" to data.ledgerCount(DeedCategory.BLOCKS, DeedAction.DAMAGED_BY, id("anvil"))
            )
        }
        val missing = counts.filterValues { count -> count <= 0L }
        check(missing.isEmpty()) { "Actions not recorded: ${missing.keys} (all counters: $counts)" }
        check(counts.getValue("obtained apple") == 3L) { "Expected 3 apples obtained, got ${counts["obtained apple"]}" }
        check(counts.getValue("obtained emerald") == 1L) { "Inventory presence must count once, got ${counts["obtained emerald"]}" }
        check(counts.getValue("obtained stick") == 4L) { "Expected 4 sticks obtained, got ${counts["obtained stick"]}" }
        check(counts.getValue("swum in water") >= 10L) { "Expected about 20 blocks swum, got ${counts["swum in water"]}" }
        check(counts.getValue("rode pig") >= 10L) { "Expected about 20 blocks ridden on the pig, got ${counts["rode pig"]}" }
    }

    private fun verifyClientSnapshot(context: ClientGameTestContext) {
        val (stoneMinedCount, stoneCompleted, toastShown) = context.onClient { client ->
            val stoneRawId = id("stone")
            val completed = ClientDeedSnapshot.instancesOf(DeedCategory.BLOCKS, stoneRawId).any(ClientDeedSnapshot::isCompleted)
            val toast = hasToast(client, DeedToast.Kind.CELL)
            Triple(ClientDeedSnapshot.ledgerCount(DeedCategory.BLOCKS, DeedAction.BROKEN, stoneRawId), completed, toast)
        }
        check(stoneMinedCount >= 1L) { "Client snapshot did not receive the mined-stone counter" }
        check(stoneCompleted) { "Client snapshot did not mark the 'mine stone' deed as completed" }
        check(toastShown) { "Expected an unlock toast" }

        // A deed is a "?" until its action has been done with the object: the planks were placed, never broken.
        val planks = context.onClient { _ ->
            val entry = TargetEntries.entryFor(DeedCategory.BLOCKS, id("oak_planks"))!!
            SnapshotAchievementUiDataSource().requirements(entry).map { row ->
                ClientDeedSnapshot.template(row.instanceIndex)?.action to row.title.string
            }
        }
        check(planks.any { (action, title) -> action == DeedAction.BROKEN && title == "?" }) { "Never-broken planks must hide 'broken': $planks" }
        check(planks.any { (action, title) -> action == DeedAction.PLACED && title != "?" }) { "Placed planks must show 'placed': $planks" }
    }

    private fun verifyScreen(context: ClientGameTestContext) {
        context.input.pressKey(EveryDeedsClient.OPEN_TABLO_SCREEN_KEY)
        context.waitForScreen(TabloScreen::class.java)
        // The screen reopens where it was left; this test starts from the top of "Blocks".
        context.onClient { client ->
            val screen = tablo(client)
            screen.selectCategory(DeedCategory.BLOCKS)
            screen.scrollTo(0.0)
        }
        context.waitTicks(5)

        // Hover the first cell (a milestone): the focus card appears.
        val cell = context.onClient { client ->
            val content = tablo(client).contentArea()
            Pair(content.x + 10 + 32, content.y + 30 + 32)
        }
        val scale = context.onClient { client -> client.window.guiScale.toDouble() }
        context.input.setCursorPos(cell.first * scale, cell.second * scale)
        context.waitTicks(10)
        context.takeScreenshot("deeds_02_focus")

        context.input.pressMouse(InputConstants.MOUSE_BUTTON_LEFT)
        context.waitTicks(5)
        context.takeScreenshot("deeds_03_popup")
        val opened = context.onClient { client -> tablo(client).openedEntry }
        check(opened != null) { "Expected the details popup after clicking a cell" }

        // Deeds not done yet with an object are "?": the planks were placed, never broken.
        context.onClient { client -> tablo(client).openDetails(TargetEntries.entryFor(DeedCategory.BLOCKS, id("oak_planks"))!!) }
        context.waitTicks(5)
        context.takeScreenshot("deeds_03_hidden_deeds")

        // Close the popup, then look at item and entity previews.
        context.input.pressKey(InputConstants.KEY_ESCAPE)
        context.waitTicks(2)
        for (category in listOf(DeedCategory.ITEMS, DeedCategory.ENTITIES, DeedCategory.BIOMES, DeedCategory.DIMENSIONS, DeedCategory.STRUCTURES)) {
            val button = context.onClient { client ->
                val rect = tablo(client).categoryArea(category)
                Pair(rect.x + 20, rect.centerY)
            }
            context.input.setCursorPos(button.first * scale, button.second * scale)
            context.input.pressMouse(InputConstants.MOUSE_BUTTON_LEFT)
            context.input.setCursorPos(2.0, 2.0)
            context.waitTicks(10)
            context.takeScreenshot("deeds_04_${category.serializedName}")
        }

        // "Show remaining" appends the not-yet-started objects (in red) after the explored ones.
        val before = context.onClient { client -> tablo(client).entryCount() }
        context.onClient { client -> tablo(client).toggleRemaining() }
        context.waitTicks(5)
        val after = context.onClient { client -> tablo(client).entryCount() }
        check(after > before) { "Expected 'show remaining' to add cells ($before -> $after)" }
        context.takeScreenshot("deeds_05_remaining")
        context.onClient { client -> tablo(client).toggleRemaining() }

        context.setScreen { null }
    }

    private fun tablo(client: Minecraft): TabloScreen = client.gui.screen() as TabloScreen

    /** The sidebar slider sends a set change; the host of a singleplayer world with cheats may use it. */
    private fun verifyDifficultySwitchPacket(context: ClientGameTestContext) {
        val allowed = context.onClient { _ -> ClientDeedSnapshot.canChangeSet }
        if (!allowed) return
        context.onClient { _ -> ClientPlayNetworking.send(ChangeDeedSetPayload("insane")) }
        context.waitFor({ ClientDeedSnapshot.set == "insane" }, 400)
    }

    /** The combinatorial sets must index and sync without trouble; the client must receive them. */
    private fun verifyHeavySets(context: ClientGameTestContext, singleplayer: TestSingleplayerContext) {
        verifyDifficultySwitchPacket(context)
        for (set in listOf("insane", "maniac")) {
            singleplayer.server.runCommand("everydeeds set $set")
            context.waitFor({ ClientDeedSnapshot.set == set }, 400)
            check(ClientDeedSnapshot.instances.isNotEmpty()) { "Set '$set' produced no deeds" }
        }
        singleplayer.server.runCommand("everydeeds set short")
        context.waitFor({ ClientDeedSnapshot.set == "short" }, 200)
    }

    private fun verifySetSwitchAndVariants(context: ClientGameTestContext, singleplayer: TestSingleplayerContext) {
        val shortSize = ClientDeedSnapshot.instances.size
        singleplayer.server.runCommand("everydeeds set extended")
        context.waitFor({ ClientDeedSnapshot.set == "extended" }, 200)
        check(ClientDeedSnapshot.instances.size > shortSize) { "Expected 'extended' to have more deeds than 'short'" }

        // Three different enchantments on one sword complete "obtain with 3 different enchantments".
        val completed = singleplayer.server.computeOnServer<Boolean, RuntimeException> { server ->
            val player = player(server)
            val enchantments = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
            val sword = ItemStack(Items.DIAMOND_SWORD)
            val mutable = ItemEnchantments.Mutable(ItemEnchantments.EMPTY)
            listOf("sharpness", "unbreaking", "mending").forEach { name ->
                mutable.set(enchantments.getOrThrow(ResourceKey.create(Registries.ENCHANTMENT, id(name))), 1)
            }
            sword.set(DataComponents.ENCHANTMENTS, mutable.toImmutable())
            sword.onCraftedBy(player, 1)

            val data = DeedPlatform.current.playerData(player)
            DeedTracker.index.instances
                .filter { instance -> instance.targetId == id("diamond_sword") && instance.definitionId.path.endsWith("obtained_enchantments") }
                .all { instance -> data.progress[instance.key]?.completed == true }
        }
        check(completed) { "Expected the enchantment-variant deed for diamond_sword to complete" }
        context.waitTicks(20)
        verifyMissingVariants(context, singleplayer)
        verifySharedProgressAndGrouping(context, singleplayer)
    }

    /**
     * On "extended" (switched from "short"): the deed done in "short" is still done, milestones pick up
     * the counts made before, mobs have their forms as variants, copper is one family cell, and
     * multi-level effects have a level deed.
     */
    private fun verifySharedProgressAndGrouping(context: ClientGameTestContext, singleplayer: TestSingleplayerContext) {
        val (stoneBroken, milestone) = singleplayer.server.computeOnServer<Pair<Boolean, Long>, RuntimeException> { server ->
            val data = DeedPlatform.current.playerData(player(server))
            val broken = DeedTracker.index.instances.first { instance ->
                instance.targetId == id("stone") && instance.definitionId.path == "extended/blocks/broken"
            }
            val milestone = DeedTracker.index.instances.first { instance ->
                instance.definition.aggregate && instance.definition.action == DeedAction.BROKEN
            }
            (data.progress[broken.key]?.completed == true) to (data.progress[milestone.key]?.count ?: 0L)
        }
        check(stoneBroken) { "The stone broken in 'short' must count in 'extended'" }
        check(milestone >= 2L) { "Milestones must pick up earlier counts, got $milestone" }

        val levelDeed = singleplayer.server.computeOnServer<Boolean, RuntimeException> { _ ->
            DeedTracker.index.instances.any { instance -> instance.definitionId.path.endsWith("effects/levels") && instance.targetId == id("slowness") }
        }
        check(levelDeed) { "Expected an effect-level deed for slowness in 'extended'" }

        // Distances: swum in liquids, ridden on vehicles; the swimming milestone picks up what was swum in "short".
        val (distanceTargets, swumMilestone) = singleplayer.server.computeOnServer<Pair<Set<String>, Long>, RuntimeException> { server ->
            val data = DeedPlatform.current.playerData(player(server))
            val distances = DeedTracker.index.instances.filter { instance -> instance.definition.action == DeedAction.TRAVELED }
            val targets = distances
                .filter { instance -> !instance.definition.aggregate && instance.definition.category != DeedCategory.BIOMES }
                .map { instance -> instance.targetId.path }.toSet()
            val milestone = distances
                .filter { instance -> instance.definition.aggregate && instance.definition.category == DeedCategory.BLOCKS }
                .maxOf { instance -> data.progress[instance.key]?.count ?: 0L }
            targets to milestone
        }
        check(listOf("water", "lava", "pig", "oak_boat", "minecart").all { target -> target in distanceTargets }) { "Distance deeds: $distanceTargets" }
        check(listOf("stone", "chest_minecart", "zombie").none { target -> target in distanceTargets }) { "Unexpected distance deeds: $distanceTargets" }
        check(swumMilestone >= 10L) { "The swimming milestone must pick up the distance swum before, got $swumMilestone" }
        val (swumLabel, waterSwum) = context.onClient { _ ->
            val water = TargetEntries.entryFor(DeedCategory.BLOCKS, id("water"))!!
            val label = (DeedAction.TRAVELED.displayName(DeedCategory.BLOCKS).contents as TranslatableContents).key
            label to SnapshotAchievementUiDataSource().statistics(water).firstOrNull { statistic -> statistic.action == DeedAction.TRAVELED }?.count
        }
        check(swumLabel == "gui.everydeeds.action.traveled.blocks") { "Liquids must say 'swum', got $swumLabel" }
        check((waterSwum ?: 0L) >= 10L) { "The water cell must show the distance swum, got $waterSwum" }
        context.setScreen { TabloScreen() }
        context.waitTicks(3)
        context.onClient { client -> tablo(client).openDetails(TargetEntries.entryFor(DeedCategory.BLOCKS, id("water"))!!) }
        context.waitTicks(5)
        context.takeScreenshot("deeds_07_swum")
        context.setScreen { null }

        val dimensions = context.onClient { client ->
            val registries = client.connection!!.registryAccess()
            fun dims(entity: String) = VariantOptions
                .enumerate(DeedVariant.EachEntityVariant.type, id(entity), registries, client.level)
                .orEmpty().filterIsInstance<VariantOption.EntityVariant>()
                .map { option -> option.dimension.toString() }.toSet()
            Triple(dims("creeper"), dims("cow"), dims("zombie"))
        }
        check("everydeeds:charged" in dimensions.first && "everydeeds:baby" !in dimensions.first) { "Creeper forms: ${dimensions.first}" }
        check("everydeeds:baby" in dimensions.second) { "Cow forms: ${dimensions.second}" }
        check("everydeeds:baby" in dimensions.third) { "Zombie forms: ${dimensions.third}" }

        val copper = context.onClient { _ ->
            val source = SnapshotAchievementUiDataSource()
            val cell = source.groups(DeedCategory.BLOCKS).flatMap { group -> group.entries }.first { entry -> entry.id == id("copper_block") }
            source.requirements(cell).mapNotNull { requirement -> requirement.member?.id?.path }
        }
        check("oxidized_copper" in copper && "waxed_copper_block" in copper) { "Copper family members: $copper" }
    }

    /**
     * "Place: 4 block states" on oak stairs is listable: the missing list comes from the server's collected
     * keys and, after one stair was placed, contains every other state. Then the list is shown on hover.
     */
    private fun verifyMissingVariants(context: ClientGameTestContext, singleplayer: TestSingleplayerContext) {
        // The goal stays hidden until the first stair is placed.
        singleplayer.server.runOnServer<RuntimeException> { server ->
            val player = player(server)
            val placeOn = player.blockPosition().relative(Direction.EAST, 2).below()
            player.level().setBlockAndUpdate(placeOn, Blocks.STONE.defaultBlockState())
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.OAK_STAIRS))
            val hit = BlockHitResult(Vec3.atCenterOf(placeOn).add(0.0, 0.5, 0.0), Direction.UP, placeOn, false)
            player.getItemInHand(InteractionHand.MAIN_HAND).useOn(UseOnContext(player, InteractionHand.MAIN_HAND, hit))
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
        }
        context.waitTicks(20)

        val stairs = id("oak_stairs")
        val source = SnapshotAchievementUiDataSource()
        val entry = context.onClient { _ -> TargetEntries.entryFor(DeedCategory.BLOCKS, stairs)!! }
        val requirement = context.onClient { _ -> source.requirements(entry).firstOrNull { requirement -> requirement.listable } }
        checkNotNull(requirement) { "Expected a listable variant goal for oak stairs" }

        var result: MissingVariants = MissingVariants.Loading
        context.waitFor({ _ ->
            result = source.missingVariants(requirement)
            result is MissingVariants.Ready
        }, 100)
        val ready = result as MissingVariants.Ready
        check(ready.total == Blocks.OAK_STAIRS.stateDefinition.possibleStates.size) { "Expected every stair state, got ${ready.total}" }
        check(ready.missing.size == ready.total - 1) { "One stair was placed, every other state must be missing: ${ready.missing.size}/${ready.total}" }

        // Visual: open the details window for the stairs and sweep the cursor down until a variant row is hovered.
        context.setScreen { TabloScreen() }
        context.waitTicks(3)
        context.onClient { client -> tablo(client).openDetails(entry) }
        val scale = context.onClient { client -> client.window.guiScale.toDouble() }
        val centerX = context.onClient { client -> client.window.guiScaledWidth / 2 - 60 }
        val height = context.onClient { client -> client.window.guiScaledHeight }
        var found = false
        for (y in 20 until height - 10 step 4) {
            context.input.setCursorPos(centerX * scale, y * scale)
            context.waitTicks(1)
            found = context.onClient { client -> tablo(client).hoveredVariantGoal != null }
            if (found) break
        }
        check(found) { "Could not hover the variant goal in the details window" }
        context.waitTicks(5)
        context.takeScreenshot("deeds_06_missing_variants")
        context.setScreen { null }
    }

    /**
     * The finale, on a tiny set of two deeds: a milestone (break dirt three times) gets the milestone
     * toast; the last deed (break gravel) completes the set: the finale toast, a full overall bar and
     * fireworks around the player that hurt nobody. Ends back on "extended".
     */
    private fun verifyFinale(context: ClientGameTestContext, singleplayer: TestSingleplayerContext) {
        singleplayer.server.runOnServer<RuntimeException> { server ->
            val player = player(server)
            // A clean spot on the surface, away from the water and blocks of the earlier checks.
            val x = player.blockX - 40
            val z = player.blockZ
            player.teleportTo(x + 0.5, player.level().getHeight(Heightmap.Types.MOTION_BLOCKING, x, z).toDouble(), z + 0.5)
            player.health = player.maxHealth

            fun deed(path: String, target: String, count: Int, milestone: Boolean) =
                Identifier.fromNamespaceAndPath("everydeeds", "$FINALE_SET/$path") to DeedDefinition(
                    DeedCategory.BLOCKS, DeedAction.BROKEN, TargetSelector.Single(id(target)), Optional.empty(), DeedGoal.Count(count),
                    Optional.empty(), Optional.empty(), milestone, if (milestone) Optional.of(id("diamond_pickaxe")) else Optional.empty()
                )
            DeedDefinitions.replace(
                DeedDefinitions.all() + mapOf(deed("test/dirt_milestone", "dirt", 3, milestone = true), deed("test/gravel", "gravel", 1, milestone = false))
            )
            check(DeedTracker.switchSet(server, FINALE_SET)) { "Could not switch to the test set" }
        }
        context.waitFor({ ClientDeedSnapshot.set == FINALE_SET }, 200)
        val start = context.onClient { _ -> SnapshotAchievementUiDataSource().overallCompletion() }
        check(start.completed == 0 && start.total == 2) { "Unexpected progress of the test set at the start: $start" }

        breakBlocks(singleplayer, Blocks.DIRT, 3)
        context.waitTicks(20)
        check(context.onClient { client -> hasToast(client, DeedToast.Kind.MILESTONE) }) { "Expected the milestone toast" }
        context.takeScreenshot("deeds_08_milestone_toast")

        breakBlocks(singleplayer, Blocks.GRAVEL, 1)
        context.onClient { client -> client.player!!.xRot = -35f }
        context.waitTicks(12)
        val (everything, rockets) = singleplayer.server.computeOnServer<Pair<Boolean, List<Boolean>>, RuntimeException> { server ->
            val player = player(server)
            DeedTracker.isEverythingComplete(player) to player.level()
                .getEntitiesOfClass(FireworkRocketEntity::class.java, player.boundingBox.inflate(16.0))
                .map(Celebration::isHarmless)
        }
        check(everything) { "The test set must be complete" }
        check(rockets.isNotEmpty() && rockets.all { harmless -> harmless }) { "Expected harmless celebration rockets around the player, got $rockets" }
        context.waitTicks(15)
        check(context.onClient { client -> hasToast(client, DeedToast.Kind.EVERYTHING) }) { "Expected the finale toast" }
        context.takeScreenshot("deeds_09_finale")

        verifyHarmlessRockets(context, singleplayer)

        context.setScreen { TabloScreen() }
        context.waitTicks(5)
        val overall = context.onClient { _ -> SnapshotAchievementUiDataSource().overallCompletion() }
        check(overall.isComplete && overall.total == 2) { "Expected the whole test set complete: $overall" }
        context.takeScreenshot("deeds_10_overall_complete")
        context.setScreen { null }

        singleplayer.server.runCommand("everydeeds set extended")
        context.waitFor({ ClientDeedSnapshot.set == "extended" }, 200)
    }

    /** A normal rocket bursting next to the player hurts them; a celebration rocket bursting at the same spot does not. */
    private fun verifyHarmlessRockets(context: ClientGameTestContext, singleplayer: TestSingleplayerContext) {
        fun healthAfterBurst(harmless: Boolean): Float {
            singleplayer.server.runOnServer<RuntimeException> { server ->
                val player = player(server)
                val level = player.level()
                player.health = player.maxHealth
                player.damageCooldownTime = 0
                // Two blocks east, under a stone ceiling: the rocket hits it and bursts within a few ticks.
                val base = player.blockPosition()
                for (dx in 1..2) for (dy in 0..1) level.setBlockAndUpdate(base.east(dx).above(dy), Blocks.AIR.defaultBlockState())
                level.setBlockAndUpdate(base.east(2).above(), Blocks.STONE.defaultBlockState())
                val x = base.x + 2.5
                val y = base.y + 0.5
                val z = base.z + 0.5
                level.addFreshEntity(if (harmless) Celebration.createRocket(level, x, y, z) else FireworkRocketEntity(level, x, y, z, plainRocket()))
            }
            context.waitTicks(15)
            return singleplayer.server.computeOnServer<Float, RuntimeException> { server -> player(server).health }
        }

        val max = singleplayer.server.computeOnServer<Float, RuntimeException> { server -> player(server).maxHealth }
        val hurt = healthAfterBurst(harmless = false)
        check(hurt < max) { "A normal rocket bursting next to the player must hurt (test setup), health $hurt/$max" }
        val safe = healthAfterBurst(harmless = true)
        check(safe == max) { "A celebration rocket must not hurt, health $safe/$max" }
    }

    private fun plainRocket(): ItemStack {
        val stack = ItemStack(Items.FIREWORK_ROCKET)
        val explosion = FireworkExplosion(FireworkExplosion.Shape.LARGE_BALL, IntList.of(0xFF5555), IntList.of(), false, false)
        stack.set(DataComponents.FIREWORKS, Fireworks(1, listOf(explosion)))
        return stack
    }

    /** Places [count] blocks of [block] in a row next to the player and breaks them as the player. */
    private fun breakBlocks(singleplayer: TestSingleplayerContext, block: Block, count: Int) {
        singleplayer.server.runOnServer<RuntimeException> { server ->
            val player = player(server)
            val start = player.blockPosition().relative(Direction.NORTH, 2)
            repeat(count) { index ->
                val pos = start.relative(Direction.WEST, index)
                player.level().setBlockAndUpdate(pos, block.defaultBlockState())
                check(player.gameMode.destroyBlock(pos)) { "Could not break $block at $pos" }
            }
        }
    }

    private fun hasToast(client: Minecraft, kind: DeedToast.Kind): Boolean =
        client.gui.toastManager().getToast(DeedToast::class.java, kind) != null

    private fun player(server: MinecraftServer): ServerPlayer = server.playerList.players.first()

    private fun id(path: String): Identifier = Identifier.withDefaultNamespace(path)

    private fun <T> ClientGameTestContext.onClient(block: (Minecraft) -> T): T =
        computeOnClient<T, RuntimeException> { client -> block(client) }

    private companion object {
        /** A two-deed set made up by the finale check. */
        const val FINALE_SET = "test_finale"
    }
}
