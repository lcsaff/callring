package com.greatwall.callring.ui.lockeddevice

/** Explicit class selection required: this test locks the phone and does not unlock it. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresLockedDevice
