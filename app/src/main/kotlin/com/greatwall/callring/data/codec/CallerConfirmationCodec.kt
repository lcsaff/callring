package com.greatwall.callring.data.codec

internal object CallerConfirmationCodec {
    const val PREFIX = "caller_confirmation:"
    const val RESTORED = "-"
    private val digest = Regex("[a-f0-9]{64}")

    fun key(archiveId: Long, resultVersion: Long): String {
        require(archiveId != 0L && resultVersion > 0)
        return "$PREFIX$archiveId:$resultVersion"
    }

    fun decode(value: String?): String = requireNotNull(value).also {
        require(it == RESTORED || digest.matches(it))
    }

    fun validate(encodedKey: String, value: String?) {
        val (archive, version) = encodedKey.removePrefix(PREFIX).split(':').also { require(it.size == 2) }
        require(key(archive.toLong(), version.toLong()) == encodedKey)
        decode(value)
    }
}
