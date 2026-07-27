package com.termux.cybersyn.core.transfer

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Bridges album art downloaded by [com.termux.cybersyn.core.contexts.FileOfferContextSource]
 * to [com.termux.cybersyn.core.contexts.MediaContextSource], which requested it and owns the
 * MediaSession it belongs on. Avoids the two context sources depending on each other directly.
 */
object AlbumArtBus {
    private val _art = MutableSharedFlow<Pair<String, Bitmap>>(extraBufferCapacity = 1)
    val art = _art.asSharedFlow()

    suspend fun emit(hash: String, bitmap: Bitmap) {
        _art.emit(hash to bitmap)
    }
}
