package io.github.monkut.wallscanner

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.MaterialShapeDrawable
import io.github.monkut.wallscanner.io.Profile

/** The climber in a circle: their photo, else the first letter of their username, else the settings gear (no profile yet). */
object Avatar {
    fun bind(view: ShapeableImageView, profile: Profile, photo: Bitmap?) {
        val c = view.context
        val size = view.layoutParams.width
        view.background = MaterialShapeDrawable(view.shapeAppearanceModel).apply { fillColor = ColorStateList.valueOf(ContextCompat.getColor(c, R.color.panel)) }
        val name = profile.username.trim()
        when {
            photo != null -> { view.setContentPadding(0, 0, 0, 0); view.imageTintList = null; view.setImageBitmap(photo) }
            name.isNotEmpty() -> { view.setContentPadding(0, 0, 0, 0); view.imageTintList = null; view.setImageBitmap(initial(view, name, size)) }
            else -> {
                val pad = size / 4
                view.setContentPadding(pad, pad, pad, pad)
                view.setImageResource(R.drawable.ic_settings)
                view.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(c, R.color.dust))
            }
        }
    }

    private fun initial(view: ShapeableImageView, name: String, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(view.context, R.color.chalk)
            textAlign = Paint.Align.CENTER
            textSize = size * 0.42f
            typeface = ResourcesCompat.getFont(view.context, R.font.atkinson_bold)
        }
        val letter = name.substring(0, name.offsetByCodePoints(0, 1)).uppercase()
        Canvas(bitmap).drawText(letter, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2, paint)
        return bitmap
    }
}
