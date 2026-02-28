package com.crossaudio.engine

enum class DrmScheme(
    val uuidString: String,
) {
    WIDEVINE("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"),
    PLAYREADY("9a04f079-9840-4286-ab92-e65be0885f95"),
    CLEAR_KEY("e2719d58-a985-b3c9-781a-b030af78d30e"),
}

data class DrmRequest(
    val scheme: DrmScheme = DrmScheme.WIDEVINE,
    val licenseUrl: String,
    val requestHeaders: Map<String, String> = emptyMap(),
    val initDataBase64: String? = null,
    val offlineLicenseId: String? = null,
    val offlineKeySetId: String? = null,
    val multiSession: Boolean = false,
    val forceL3: Boolean = false,
)

data class DrmGlobalConfig(
    val allowOfflineLicenses: Boolean = true,
    val requestTimeoutMs: Int = 15_000,
    val retryCount: Int = 2,
)

sealed class OfflineLicenseResult {
    data class Success(
        val licenseId: String,
        val keySetId: String,
        val expiresAtEpochMs: Long? = null,
    ) : OfflineLicenseResult()

    data class Failure(
        val message: String,
        val causeClass: String? = null,
    ) : OfflineLicenseResult()
}
