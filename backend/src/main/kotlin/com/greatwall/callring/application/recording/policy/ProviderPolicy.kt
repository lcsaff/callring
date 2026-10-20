package com.greatwall.callring.application.recording.policy

import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.application.recording.model.AiConnection
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.domain.ai.validate as validateDomain
import com.greatwall.callring.domain.ai.normalized as normalizedDomain
import com.greatwall.callring.domain.ai.blockReason as blockReasonDomain

fun AiConnection.domainSpec() = com.greatwall.callring.domain.ai.ConnectionSpec(protocol.toDomain(), url, apiKey, model)
fun ConversationBudget.validate() = toDomain().validateDomain()
fun resolveConversationProfile(connection: AiConnection, compatibility: ConversationCompatibility = ConversationCompatibility.AUTO): ProviderProfile =
    com.greatwall.callring.domain.ai.resolveConversationProfile(connection.domainSpec(), compatibility.toDomain()).toContract()
fun ConversationCompatibility.normalized(): ConversationCompatibility = toDomain().normalizedDomain().toContract()

/** Boundary projection of a native policy. Rules and parameter selection stay in domain.ai. */
data class ThinkingPolicy internal constructor(internal val native: com.greatwall.callring.domain.ai.ThinkingPolicy) {
    val contract get() = native.contract
    val protocol get() = native.protocol.toContract()
    val support get() = native.support.toContract()
    val disable get() = native.disable
    val enable get() = native.enable
    val reason get() = native.reason
    val officialHost get() = native.officialHost
    fun parameters(mode: ThinkingMode) = native.parameters(mode.toDomain())
}
object ThinkingPolicyResolver {
    fun contractOf(model: String) = com.greatwall.callring.domain.ai.ThinkingPolicyResolver.contractOf(model)
    fun resolve(protocol: AiProtocol, url: String, model: String): ThinkingPolicy =
        ThinkingPolicy(com.greatwall.callring.domain.ai.ThinkingPolicyResolver.resolve(protocol.toDomain(), url, model))
}
fun ThinkingPolicy.blockReason(): String? = native.blockReasonDomain()
