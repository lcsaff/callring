package com.greatwall.callring.contracts.command

data class RevisionVector(val incarnation: String, val values: Map<String, Long>)
/** Composite deletion can advance through its own guarded commits; foreign changes still conflict. */

data class CommandContext(val operationId: String, val expectedVersions: RevisionVector, val sessionGeneration: Long, val mayCommit: () -> Boolean = { true }, val expectedScopes:Map<String,Long>?=null, val continueOwnCommits: Boolean = false, val active: () -> Boolean)
