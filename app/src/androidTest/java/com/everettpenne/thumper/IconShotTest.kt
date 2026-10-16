package com.everettpenne.thumper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.io.File

/** Draws the launcher icon the way a launcher would (rounded mask, then the themed one-colour layer on a tinted ground) and saves it for a look. */
class IconShotTest {
    @Test fun renderLauncherIcon() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "shots").apply { mkdirs() }
        val icon = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        val size = 512; val bleed = size / 4
        fun render(name: String, draw: (Canvas) -> Unit, circle: Boolean) {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); val c = Canvas(bitmap)
            c.clipPath(Path().apply { if (circle) addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) else addRoundRect(0f, 0f, size.toFloat(), size.toFloat(), size * .24f, size * .24f, Path.Direction.CW) })
            draw(c)
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // the launcher shows the middle 72 of the layers' 108: draw them 1.5x the size, centred, then mask
        val full = { c: Canvas -> for (layer in listOfNotNull(icon.background, icon.foreground)) { layer.setBounds(-bleed, -bleed, size + bleed, size + bleed); layer.draw(c) } }
        render("icon-squircle", full, circle = false)
        render("icon-circle", full, circle = true)
        // themed: the monochrome layer in one colour on a soft ground
        val mono = icon.monochrome!!.mutate()
        render("icon-themed", { c -> c.drawColor(Color.rgb(46, 56, 90)); mono.setTint(Color.rgb(196, 214, 255)); mono.setBounds(-bleed, -bleed, size + bleed, size + bleed); mono.draw(c) }, circle = true)
    }
}
