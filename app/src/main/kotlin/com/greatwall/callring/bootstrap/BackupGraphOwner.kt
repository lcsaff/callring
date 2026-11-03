package com.greatwall.callring.bootstrap

/** Production Application owns replacing the graph and restarting its UI task. */
fun interface BackupGraphOwner {
    fun replace(expected: BackendGraph, mutation: () -> Unit): Boolean
}
