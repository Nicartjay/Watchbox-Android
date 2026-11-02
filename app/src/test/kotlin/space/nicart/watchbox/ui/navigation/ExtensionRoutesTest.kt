package space.nicart.watchbox.ui.navigation

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import space.nicart.watchbox.extension.SourceCollection
import kotlin.test.Test
import kotlin.test.assertEquals

class ExtensionRoutesTest {
    @Test
    fun `phone Browse and its Extensions page include both normal and short providers`() {
        assertEquals(SourceCollection.ALL, AppTab.BROWSE.extensionCollection(separateShortReels = false))
        val route = Routes.Extensions(AppTab.BROWSE.extensionCollection(separateShortReels = false))
        assertEquals(SourceCollection.ALL, route.collection)
    }

    @Test
    fun `each section opens its own extension collection`() {
        assertEquals(SourceCollection.STANDARD, AppTab.BROWSE.extensionCollection())
        assertEquals(SourceCollection.SHORT_REELS, AppTab.SHORT_REELS.extensionCollection())
    }

    @Test
    fun `extension scope survives route serialization`() {
        SourceCollection.entries.forEach { collection ->
            val route = Routes.Extensions(collection)
            assertEquals(route, Json.decodeFromString<Routes.Extensions>(Json.encodeToString(route)))
        }
    }

    @Test
    fun `legacy extension routes keep their unscoped default`() {
        assertEquals(SourceCollection.ALL, Json.decodeFromString<Routes.Extensions>("{}").collection)
    }
}
