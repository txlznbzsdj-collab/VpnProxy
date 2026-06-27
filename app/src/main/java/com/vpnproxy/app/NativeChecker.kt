package com.vpnproxy.app

object NativeChecker {
    private var loaded = false

    fun load() {
        if (!loaded) {
            try {
                System.loadLibrary("security_check")
                loaded = true
            } catch (_: UnsatisfiedLinkError) {
                loaded = false
            }
        }
    }

    fun isLoaded(): Boolean = loaded

    external fun ptraceMe(): Boolean
    external fun checkTracerPid(): Boolean
    external fun checkMaps(): Boolean
    external fun checkThreads(): Boolean
}
