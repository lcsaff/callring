package com.greatwall.callring.ui.overlay

import com.greatwall.callring.ui.app.UiApiOwner

/** Application lifetime keeps dismissed session identities across service restarts. */
interface UiOverlayOwner : UiApiOwner { val overlaySessions: OverlaySessions }
