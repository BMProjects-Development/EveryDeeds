package ru.benos.everydeeds.gametest

import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.resources.language.ClientLanguage
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import ru.benos.everydeeds.EveryDeeds.MOD_ID
import ru.benos.everydeeds.EveryDeeds.literal
import ru.benos.everydeeds.client.deed.ClientDeedSnapshot
import ru.benos.everydeeds.client.gui.DeedToast
import ru.benos.everydeeds.client.gui.TabloScreen
import ru.benos.everydeeds.client.gui.deedSetName
import ru.benos.everydeeds.deed.DeedCategory
import java.util.concurrent.CompletableFuture

/**
 * Every language file of the mod translates every key. Switching the language while a world is open
 * reloads every client resource: previews cached by the progress screen (block scenes, entities, item
 * entities) must survive that and keep rendering, and the toasts must fit their titles in each language.
 */
class LanguageReloadClientTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        verifyEveryLanguageComplete(context)
        context.worldBuilder().create().use { singleplayer ->
            singleplayer.connection.waitForChunksRender()
            context.waitFor({ ClientDeedSnapshot.isSynced }, 200)

            openAllCategories(context, "before", DeedCategory.entries)
            for (language in listOf("ru_ru", "de_de", "zh_cn", "en_us")) {
                context.setScreen { PauseScreen(true) }
                switchLanguage(context, language)
                // Russian and English are the reference; the other languages only get a look at the longest texts.
                val categories = if (language == "ru_ru" || language == "en_us") DeedCategory.entries else listOf(DeedCategory.STRUCTURES)
                openAllCategories(context, language, categories)
                showToasts(context, language)
            }
            context.setScreen { null }
        }
    }

    /** Loads each language file of the mod on its own (no resource reload) and checks it translates every English key. */
    private fun verifyEveryLanguageComplete(context: ClientGameTestContext) {
        val untranslated = context.computeOnClient<Map<String, List<String>>, RuntimeException> { client ->
            val resources = client.resourceManager
            val englishKeys = resources.getResourceOrThrow(Identifier.fromNamespaceAndPath(MOD_ID, "lang/en_us.json")).openAsReader()
                .use { reader -> JsonParser.parseReader(reader).asJsonObject.keySet().toList() }
            val languages = resources.listResources("lang") { id -> id.namespace == MOD_ID && id.path.endsWith(".json") }.keys
                .map { id -> id.path.substringAfterLast('/').removeSuffix(".json") }
            languages.associateWith { code ->
                val language = ClientLanguage.loadFrom(resources, listOf(code), false)
                englishKeys.filterNot(language::has)
            }.filterValues { keys -> keys.isNotEmpty() }
        }
        check(untranslated.isEmpty()) { "Language files missing keys: $untranslated" }
    }

    /** A toast of each kind (the milestone one with two unlocks, for its plural title) in the current language. */
    private fun showToasts(context: ClientGameTestContext, language: String) {
        context.computeOnClient<Unit, RuntimeException> { client ->
            val toasts = client.gui.toastManager()
            toasts.clear()
            DeedToast.show(toasts, DeedToast.Kind.EVERYTHING, ItemStack(Items.NETHER_STAR), deedSetName("maniac"))
            DeedToast.show(toasts, DeedToast.Kind.MILESTONE, ItemStack(Items.DIAMOND_PICKAXE), "10 000".literal)
            DeedToast.show(toasts, DeedToast.Kind.MILESTONE, ItemStack(Items.BRICKS), "100 000".literal)
            DeedToast.show(toasts, DeedToast.Kind.CELL, ItemStack(Items.STONE), ItemStack(Items.STONE).hoverName)
        }
        context.waitTicks(30)
        context.takeScreenshot("lang_${language}_toasts")
        context.computeOnClient<Unit, RuntimeException> { client -> client.gui.toastManager().clear() }
    }

    private fun switchLanguage(context: ClientGameTestContext, language: String) {
        val reload = context.computeOnClient<CompletableFuture<Void>, RuntimeException> { client ->
            client.languageManager.setSelected(language)
            client.options.languageCode = language
            client.reloadResourcePacks()
        }
        context.waitFor({ reload.isDone }, 1200)
        context.waitTicks(40)
    }

    /** Opens the progress screen with the not-started objects shown in every category, so all preview kinds are drawn. */
    private fun openAllCategories(context: ClientGameTestContext, label: String, categories: List<DeedCategory>) {
        context.setScreen { TabloScreen() }
        context.waitTicks(3)
        context.computeOnClient<Unit, RuntimeException> { client -> tablo(client).toggleRemaining() }
        for (category in categories) {
            context.computeOnClient<Unit, RuntimeException> { client -> tablo(client).selectCategory(category) }
            context.input.setCursorPos(900.0, 400.0)
            context.waitTicks(20)
            context.takeScreenshot("lang_${label}_${category.serializedName}")
        }
        context.computeOnClient<Unit, RuntimeException> { client -> tablo(client).toggleRemaining() }
    }

    private fun tablo(client: Minecraft): TabloScreen = client.gui.screen() as TabloScreen
}
