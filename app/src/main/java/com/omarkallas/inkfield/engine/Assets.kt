package com.omarkallas.inkfield.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * Images placed in drawings. Files live in app storage under assets/, named by
 * id, and are shared by drawings that reference them (duplicates don't copy them).
 */
object Assets {
    private var dir: File? = null
    private const val MAX_SIDE = 4096

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun init(context: Context) {
        dir = File(context.filesDir, "assets").apply { mkdirs() }
    }

    fun file(name: String) = File(dir ?: File("."), name)

    /** Saves [bmp] as a new asset (PNG keeps transparency) and returns its name. */
    fun save(bmp: Bitmap): String? {
        val scaled = if (max(bmp.width, bmp.height) > MAX_SIDE) {
            val k = MAX_SIDE.toFloat() / max(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
        } else bmp
        val name = UUID.randomUUID().toString() + if (scaled.hasAlpha()) ".png" else ".jpg"
        return runCatching {
            file(name).outputStream().use {
                scaled.compress(if (name.endsWith(".png")) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it)
            }
            cache.put(name, scaled)
            name
        }.getOrNull()
    }

    /** Decodes an image stream with downsampling so huge photos don't exhaust memory. */
    fun decode(open: () -> java.io.InputStream?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return open()?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    fun get(name: String?): Bitmap? {
        if (name == null) return null
        cache.get(name)?.let { return it }
        val f = file(name)
        if (!f.exists()) return null
        val bmp = decode { f.inputStream() } ?: return null
        cache.put(name, bmp)
        return bmp
    }

    private val masks = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** The image's alpha channel only, for fills that are tinted with their stroke color. */
    @Synchronized
    fun alphaMask(name: String?): Bitmap? {
        if (name == null) return null
        masks.get(name)?.let { return it }
        val src = get(name) ?: return null
        val m = src.extractAlpha()
        masks.put(name, m)
        return m
    }

    fun bytes(name: String): ByteArray? = runCatching { file(name).readBytes() }.getOrNull()

    /** Deletes asset files no drawing references any more. */
    fun collect(referenced: Set<String>) {
        // Only old files: anything newer may belong to a drawing that hasn't autosaved yet.
        val cutoff = System.currentTimeMillis() - 24L * 3600 * 1000
        dir?.listFiles()?.forEach { f -> if (f.name !in referenced && f.lastModified() < cutoff) f.delete() }
    }
}
