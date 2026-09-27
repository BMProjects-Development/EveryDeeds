package ru.benos.everydeeds.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import ru.benos.everydeeds.EveryDeeds
import ru.benos.everydeeds.data.DeedDefinitions
import ru.benos.everydeeds.platform.DeedPlatform
import ru.benos.everydeeds.tracking.DeedSync
import ru.benos.everydeeds.tracking.DeedTracker

/**
 * `/everydeeds info`                 - active set and the caller's completion.
 * `/everydeeds sets`                 - loaded sets.
 * `/everydeeds set <set>`            - switch the world's set (progress of other sets is kept).
 * `/everydeeds reset <players>`      - wipe progress and counters.
 */
object DeedCommands {
    private const val ROOT = "everydeeds"

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal(ROOT)
                .then(Commands.literal("info").executes(::info))
                .then(Commands.literal("sets").executes(::listSets))
                .then(
                    Commands.literal("set")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(
                            Commands.argument("set", StringArgumentType.word())
                                .suggests { _, builder -> SharedSuggestionProvider.suggest(DeedDefinitions.sets(), builder) }
                                .executes(::setActiveSet)
                        )
                )
                .then(
                    Commands.literal("reset")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("targets", EntityArgument.players()).executes(::reset))
                )
        )
    }

    private fun info(context: CommandContext<CommandSourceStack>): Int {
        val index = DeedTracker.index
        val player = context.source.player
        val completed = player?.let { serverPlayer ->
            val data = DeedPlatform.current.playerData(serverPlayer)
            index.instances.count { instance -> data.progress[instance.key]?.completed == true }
        }

        context.source.sendSuccess({
            Component.translatable("commands.${EveryDeeds.MOD_ID}.info.set", index.set, index.instances.size)
                .let { line ->
                    if (completed == null) line
                    else line.append(" ").append(Component.translatable("commands.${EveryDeeds.MOD_ID}.info.completed", completed, index.instances.size))
                }
        }, false)
        return completed ?: 0
    }

    private fun listSets(context: CommandContext<CommandSourceStack>): Int {
        val sets = DeedDefinitions.sets()
        context.source.sendSuccess({
            Component.translatable("commands.${EveryDeeds.MOD_ID}.sets", sets.joinToString(", "))
        }, false)
        return sets.size
    }

    private fun setActiveSet(context: CommandContext<CommandSourceStack>): Int {
        val set = StringArgumentType.getString(context, "set")
        if (set !in DeedDefinitions.sets()) {
            context.source.sendFailure(Component.translatable("commands.${EveryDeeds.MOD_ID}.set.unknown", set))
            return 0
        }

        DeedTracker.switchSet(context.source.server, set)
        context.source.sendSuccess({
            Component.translatable("commands.${EveryDeeds.MOD_ID}.set.success", set, DeedTracker.index.instances.size)
        }, true)
        return 1
    }

    private fun reset(context: CommandContext<CommandSourceStack>): Int {
        val players = EntityArgument.getPlayers(context, "targets")
        for (player in players) {
            DeedPlatform.current.playerData(player).clear()
            DeedSync.sendFullSync(player)
        }
        context.source.sendSuccess({
            Component.translatable("commands.${EveryDeeds.MOD_ID}.reset.success", players.size)
        }, true)
        return players.size
    }
}
