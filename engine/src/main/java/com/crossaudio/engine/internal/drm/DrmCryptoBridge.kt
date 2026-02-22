package com.crossaudio.engine.internal.drm

import android.media.MediaCrypto
import android.media.MediaDrm
import android.util.Base64
import java.util.UUID

internal object DrmCryptoBridge {
    fun openMediaCrypto(uuid: UUID, keySetIdB64: String?): MediaCrypto? {
        val keySet = keySetIdB64?.takeIf { it.isNotBlank() } ?: return null
        val keySetBytes = runCatching { Base64.decode(keySet, Base64.DEFAULT) }.getOrNull() ?: return null
        return runCatching {
            val drm = MediaDrm(uuid)
            val sessionId = drm.openSession()
            drm.restoreKeys(sessionId, keySetBytes)
            MediaCrypto(uuid, sessionId)
        }.getOrNull()
    }
}
