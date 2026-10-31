package jo.autofare.service.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleToggleTest {

    private val apps = setOf("jeeny", "petra")

    @Test
    fun `a tap while armed pauses everything and remembers what was on`() {
        assertEquals(
            BubbleTap.Apply(armed = emptySet(), remember = setOf("jeeny")),
            bubbleTap(armedNow = setOf("jeeny"), available = apps, paused = emptySet()),
        )
    }

    @Test
    fun `the next tap brings back exactly what was paused, not everything`() {
        assertEquals(
            BubbleTap.Apply(armed = setOf("jeeny"), remember = emptySet()),
            bubbleTap(armedNow = emptySet(), available = apps, paused = setOf("jeeny")),
        )
    }

    @Test
    fun `with nothing to bring back it opens the app instead of arming on defaults`() {
        assertEquals(BubbleTap.OpenApp, bubbleTap(emptySet(), apps, emptySet()))
    }

    @Test
    fun `an app no longer supported is neither paused nor brought back`() {
        assertEquals(BubbleTap.OpenApp, bubbleTap(emptySet(), apps, setOf("careem")))
        assertEquals(
            BubbleTap.Apply(armed = emptySet(), remember = setOf("petra")),
            bubbleTap(setOf("petra", "careem"), apps, emptySet()),
        )
    }

    @Test
    fun `a saved position is pulled back onto a smaller screen`() {
        assertEquals(1080 - 150 to 2000 - 150, clampBubble(2000, 3000, 150, 1080, 2000))
        assertEquals(0 to 0, clampBubble(-40, -10, 150, 1080, 2000))
        assertEquals(300 to 900, clampBubble(300, 900, 150, 1080, 2000))
    }
}
