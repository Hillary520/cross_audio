package com.crossaudio.engine.internal.network

import android.content.ContentResolver
import android.media.MediaExtractor

internal object HttpMediaSource {
    fun applyToExtractor(
        extractor: MediaExtractor,
        source: ResolvedMediaSource,
        contentResolver: ContentResolver,
    ) {
        when (source) {
            is ResolvedMediaSource.LocalFile -> extractor.setDataSource(source.path)
            is ResolvedMediaSource.RemoteHttp -> extractor.setDataSource(source.url, source.headers)
            is ResolvedMediaSource.LocalUri -> {
                contentResolver.openAssetFileDescriptor(source.uri, "r")?.use { afd ->
                    extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    return
                }
                contentResolver.openFileDescriptor(source.uri, "r")?.use { pfd ->
                    extractor.setDataSource(pfd.fileDescriptor)
                    return
                }
                throw IllegalArgumentException("Unable to open Uri: ${source.uri}")
            }
        }
    }
}
