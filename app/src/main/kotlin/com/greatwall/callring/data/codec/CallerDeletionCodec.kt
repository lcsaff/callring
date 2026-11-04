package com.greatwall.callring.data.codec

import com.greatwall.callring.domain.model.NumberIdentity

internal object CallerDeletionCodec {
    const val PREFIX = "caller_deleted_at:"
    fun key(number: String): String {
        val identity=NumberIdentity.parse(number)
        require(identity.validProfileNumber && identity.normalized==number)
        return PREFIX+number
    }
    fun decode(value: String?): Long = requireNotNull(value?.toLongOrNull()).also { require(it>=0) }
    fun validate(key: String,value: String?) { require(key(key.removePrefix(PREFIX))==key); decode(value) }
}
