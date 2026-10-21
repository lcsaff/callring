package com.greatwall.callring.application.recording.model

/** Live counts sampled individually; this observation is not an atomic workflow snapshot. */
data class RecordingActivityObservation(val running: Long, val activeClients: Long, val cancelledOperations: Long)
