package com.crossaudio.engine.internal.drm

import android.content.Context
import android.media.MediaCrypto
import android.media.MediaDrm
import android.util.Base64
import com.crossaudio.engine.DrmGlobalConfig
import com.crossaudio.engine.DrmRequest
import com.crossaudio.engine.DrmScheme
import com.crossaudio.engine.EngineTelemetryEvent
import com.crossaudio.engine.OfflineLicenseResult
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

internal data class ActiveDrmSession(
    val scheme: DrmScheme,
    val mediaDrm: MediaDrm,
    val sessionId: ByteArray,
    val mediaCrypto: MediaCrypto,
)

internal class DrmSessionManager(
    context: Context,
    private val telemetry: (EngineTelemetryEvent) -> Unit,
) {
    private val store = OfflineLicenseStore(context)
    private val licenseClient = WidevineLicenseClient()

    @Volatile
    private var globalConfig = DrmGlobalConfig()

    fun setGlobalConfig(config: DrmGlobalConfig) {
        globalConfig = config
    }

    fun acquireOfflineLicense(
        mediaKey: String,
        request: DrmRequest,
        manifestInitDataBase64: String? = null,
    ): OfflineLicenseResult {
        if (!globalConfig.allowOfflineLicenses) {
            return OfflineLicenseResult.Failure("Offline licenses are disabled by config")
        }
        if (request.scheme != DrmScheme.WIDEVINE) {
            return OfflineLicenseResult.Failure("Unsupported DRM scheme: ${request.scheme}")
        }

        request.offlineKeySetId?.takeIf { it.isNotBlank() }?.let { keySetId ->
            val licenseId = "wv_${System.currentTimeMillis()}"
            store.put(
                OfflineLicenseRecord(
                    licenseId = licenseId,
                    mediaKey = mediaKey,
                    groupKey = mediaKey,
                    scheme = request.scheme.name,
                    keySetIdB64 = keySetId,
                    createdAtMs = System.currentTimeMillis(),
                ),
            )
            telemetry(EngineTelemetryEvent.OfflineLicenseUsed(licenseId))
            return OfflineLicenseResult.Success(licenseId = licenseId, keySetId = keySetId)
        }

        val psshB64 = DrmInitDataResolver.resolveOfflineInitData(request, manifestInitDataBase64)
            ?: return OfflineLicenseResult.Failure(
                "Missing offlineKeySetId/initDataBase64/manifest init-data/$HEADER_PSSH_B64 for acquisition",
                IllegalArgumentException::class.java.name,
            )

        return runCatching {
            val initData = Base64.decode(psshB64, Base64.DEFAULT)
            val uuid = UUID.fromString(request.scheme.uuidString)
            val drm = MediaDrm(uuid)
            if (request.forceL3) {
                runCatching { drm.setPropertyString("securityLevel", "L3") }
            }

            val session = drm.openSession()
            val requestHeaders = request.requestHeaders.filterKeys { it != HEADER_PSSH_B64 }
            val keyRequest = drm.getKeyRequest(
                session,
                initData,
                null,
                MediaDrm.KEY_TYPE_OFFLINE,
                HashMap(requestHeaders),
            )
            val response = executeLicenseRequestWithRetry(
                licenseUrl = request.licenseUrl,
                body = keyRequest.data,
                headers = requestHeaders,
            )
            val keySet = drm.provideKeyResponse(session, response)
            drm.closeSession(session)
            closeDrm(drm)

            val keySetB64 = Base64.encodeToString(keySet, Base64.NO_WRAP)
            val licenseId = "wv_${System.currentTimeMillis()}"
            store.put(
                OfflineLicenseRecord(
                    licenseId = licenseId,
                    mediaKey = mediaKey,
                    groupKey = mediaKey,
                    scheme = request.scheme.name,
                    keySetIdB64 = keySetB64,
                    createdAtMs = System.currentTimeMillis(),
                ),
            )
            telemetry(EngineTelemetryEvent.DrmSessionOpened(request.scheme.name))
            OfflineLicenseResult.Success(licenseId = licenseId, keySetId = keySetB64)
        }.getOrElse { t ->
            telemetry(EngineTelemetryEvent.DrmSessionFailed(t.message ?: "DRM session failed"))
            OfflineLicenseResult.Failure(
                message = t.message ?: "DRM session failed",
                causeClass = t::class.java.name,
            )
        }
    }

    fun openPlaybackSession(
        mediaKey: String,
        request: DrmRequest,
        manifestInitDataBase64: String? = null,
    ): ActiveDrmSession {
        require(request.scheme == DrmScheme.WIDEVINE) { "Unsupported DRM scheme: ${request.scheme}" }

        val uuid = UUID.fromString(request.scheme.uuidString)
        val drm = MediaDrm(uuid)
        var sessionId: ByteArray? = null
        try {
            if (request.forceL3) {
                runCatching { drm.setPropertyString("securityLevel", "L3") }
            }
            val opened = drm.openSession()
            sessionId = opened

            val restoredFromLicenseId = request.offlineLicenseId
                ?.takeIf { it.isNotBlank() }
                ?.let { id -> store.get(id)?.keySetIdB64 to id }
            val restoredFromRequestKeySet = request.offlineKeySetId
                ?.takeIf { it.isNotBlank() }
                ?.let { it to null }
            val resolvedRestore = restoredFromLicenseId ?: restoredFromRequestKeySet
            if (resolvedRestore != null) {
                val keySet = Base64.decode(resolvedRestore.first, Base64.DEFAULT)
                drm.restoreKeys(opened, keySet)
                resolvedRestore.second?.let { telemetry(EngineTelemetryEvent.OfflineLicenseUsed(it)) }
            } else {
                val initDataB64 = DrmInitDataResolver.resolveStreamingInitData(request, manifestInitDataBase64)
                    ?: throw IllegalArgumentException("Missing streaming initData for DRM playback")
                val initData = Base64.decode(initDataB64, Base64.DEFAULT)
                val keyRequest = drm.getKeyRequest(
                    opened,
                    initData,
                    null,
                    MediaDrm.KEY_TYPE_STREAMING,
                    HashMap(request.requestHeaders.filterKeys { it != HEADER_PSSH_B64 }),
                )
                val response = executeLicenseRequestWithRetry(
                    licenseUrl = request.licenseUrl,
                    body = keyRequest.data,
                    headers = request.requestHeaders.filterKeys { it != HEADER_PSSH_B64 },
                )
                drm.provideKeyResponse(opened, response)
            }

            val mediaCrypto = MediaCrypto(uuid, opened)
            telemetry(EngineTelemetryEvent.DrmSessionOpened(request.scheme.name))
            return ActiveDrmSession(
                scheme = request.scheme,
                mediaDrm = drm,
                sessionId = opened,
                mediaCrypto = mediaCrypto,
            )
        } catch (t: Throwable) {
            sessionId?.let { runCatching { drm.closeSession(it) } }
            closeDrm(drm)
            telemetry(EngineTelemetryEvent.DrmSessionFailed(t.message ?: "DRM session failed"))
            throw t
        }
    }

    fun closePlaybackSession(session: ActiveDrmSession) {
        runCatching { session.mediaCrypto.release() }
        runCatching { session.mediaDrm.closeSession(session.sessionId) }
        closeDrm(session.mediaDrm)
        telemetry(EngineTelemetryEvent.DrmSessionClosed(session.scheme.name))
    }

    fun releaseOfflineLicense(licenseId: String) {
        val record = store.get(licenseId) ?: return
        val releaseResult = runCatching {
            val uuid = UUID.fromString(DrmScheme.WIDEVINE.uuidString)
            val keySet = Base64.decode(record.keySetIdB64, Base64.DEFAULT)
            val drm = MediaDrm(uuid)
            try {
                drm.removeOfflineLicense(keySet)
            } finally {
                closeDrm(drm)
            }
        }
        releaseResult
            .onSuccess { store.remove(licenseId) }
            .onFailure { telemetry(EngineTelemetryEvent.DrmSessionFailed("Failed to release offline license")) }
    }

    fun release() {
        store.close()
    }

    companion object {
        private const val HEADER_PSSH_B64 = "X-CrossAudio-Pssh-Base64"
    }

    private fun closeDrm(drm: MediaDrm) {
        runCatching { drm.close() }.onFailure {
            @Suppress("DEPRECATION")
            runCatching { drm.release() }
        }
    }

    private fun executeLicenseRequestWithRetry(
        licenseUrl: String,
        body: ByteArray,
        headers: Map<String, String>,
    ): ByteArray {
        val timeoutMs = globalConfig.requestTimeoutMs.coerceAtLeast(1_000)
        val retries = globalConfig.retryCount.coerceAtLeast(0)
        var lastError: Throwable? = null

        repeat(retries + 1) { attempt ->
            try {
                return licenseClient.executeKeyRequest(
                    licenseUrl = licenseUrl,
                    body = body,
                    headers = headers,
                    timeoutMs = timeoutMs,
                )
            } catch (t: Throwable) {
                if (t is InterruptedException) throw t
                lastError = t
                if (attempt < retries) {
                    sleepBackoff(250L * (attempt + 1))
                }
            }
        }
        throw lastError ?: IllegalStateException("License request failed")
    }

    private fun sleepBackoff(delayMs: Long) {
        if (delayMs <= 0L) return
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Interrupted before DRM retry backoff")
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(delayMs.coerceAtMost(3_000L)))
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Interrupted during DRM retry backoff")
    }
}
