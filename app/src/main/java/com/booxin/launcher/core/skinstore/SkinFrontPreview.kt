package com.booxin.launcher.core.skinstore

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

/**
 * Composites a front-facing Steve/Alex body from a Minecraft skin PNG
 * (list thumbnails show a model, not the raw texture atlas).
 */
object SkinFrontPreview {

    private val nearest = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = false
        isAntiAlias = false
    }

    fun render(texture: Bitmap, slim: Boolean, unitPx: Int = 6): Bitmap {
        val scale = unitPx.coerceIn(2, 12)
        val armW = if (slim) 3 else 4
        val outW = (armW + 8 + armW) * scale
        val outH = 32 * scale
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val src = normalizeTo64(texture)

        // Character faces the camera: their right arm is on the viewer's left.
        blit(canvas, src, 8, 8, 8, 8, armW, 0, 8, 8, scale) // head
        blit(canvas, src, 40, 8, 8, 8, armW, 0, 8, 8, scale, overlay = true) // hat
        blit(canvas, src, 20, 20, 8, 12, armW, 8, 8, 12, scale) // body
        blit(canvas, src, 20, 36, 8, 12, armW, 8, 8, 12, scale, overlay = true) // jacket
        blit(canvas, src, 44, 20, armW, 12, 0, 8, armW, 12, scale) // right arm
        blit(canvas, src, if (slim) 52 else 44, 36, armW, 12, 0, 8, armW, 12, scale, overlay = true)
        blit(canvas, src, 36, 52, armW, 12, armW + 8, 8, armW, 12, scale) // left arm
        blit(canvas, src, if (slim) 44 else 52, 52, armW, 12, armW + 8, 8, armW, 12, scale, overlay = true)
        blit(canvas, src, 4, 20, 4, 12, armW, 20, 4, 12, scale) // right leg
        blit(canvas, src, 4, 36, 4, 12, armW, 20, 4, 12, scale, overlay = true)
        blit(canvas, src, 20, 52, 4, 12, armW + 4, 20, 4, 12, scale) // left leg
        blit(canvas, src, 4, 52, 4, 12, armW + 4, 20, 4, 12, scale, overlay = true)

        if (src !== texture) src.recycle()
        return out
    }

    fun isSlimModel(model: String?): Boolean =
        model?.equals("slim", ignoreCase = true) == true

    private fun normalizeTo64(src: Bitmap): Bitmap {
        if (src.width == 64 && src.height == 64) return src
        val out = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(src, null, Rect(0, 0, 64, if (src.height >= 64) 64 else 32), nearest)
        if (src.height < 64) {
            // Classic 64×32: mirror right limb to left slots.
            copyRect(out, 0, 16, 16, 16, 16, 48)
            copyRect(out, 40, 16, 16, 16, 32, 48)
        }
        return out
    }

    private fun copyRect(bmp: Bitmap, sx: Int, sy: Int, w: Int, h: Int, dx: Int, dy: Int) {
        val patch = Bitmap.createBitmap(bmp, sx, sy, w, h)
        val c = Canvas(bmp)
        c.drawBitmap(patch, dx.toFloat(), dy.toFloat(), nearest)
        patch.recycle()
    }

    private fun blit(
        canvas: Canvas,
        src: Bitmap,
        sx: Int,
        sy: Int,
        sw: Int,
        sh: Int,
        dxUnits: Int,
        dyUnits: Int,
        dwUnits: Int,
        dhUnits: Int,
        scale: Int,
        overlay: Boolean = false
    ) {
        if (sw <= 0 || sh <= 0) return
        if (sx + sw > src.width || sy + sh > src.height) return
        if (overlay && !regionHasOpaque(src, sx, sy, sw, sh)) return
        val patch = Bitmap.createBitmap(src, sx, sy, sw, sh)
        val dst = Rect(
            dxUnits * scale,
            dyUnits * scale,
            (dxUnits + dwUnits) * scale,
            (dyUnits + dhUnits) * scale
        )
        canvas.drawBitmap(patch, null, dst, nearest)
        patch.recycle()
    }

    private fun regionHasOpaque(src: Bitmap, x: Int, y: Int, w: Int, h: Int): Boolean {
        var opaque = 0
        val x1 = (x + w).coerceAtMost(src.width)
        val y1 = (y + h).coerceAtMost(src.height)
        for (py in y until y1) {
            for (px in x until x1) {
                if ((src.getPixel(px, py) ushr 24) >= 8) {
                    opaque++
                    if (opaque >= 6) return true
                }
            }
        }
        return false
    }
}
