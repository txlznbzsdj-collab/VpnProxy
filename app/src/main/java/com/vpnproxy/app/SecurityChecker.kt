package com.vpnproxy.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object SecurityChecker {

    fun isDebugged(): Boolean {
        if (NativeChecker.isLoaded()) {
            if (NativeChecker.ptraceMe()) return true
            if (NativeChecker.checkTracerPid()) return true
        } else {
            if (android.os.Debug.isDebuggerConnected()) return true
            if (checkTracerPidJava()) return true
        }
        return false
    }

    fun isEmulator(): Boolean {
        return Build.FINGERPRINT.contains("generic") ||
               Build.FINGERPRINT.contains("vbox") ||
               Build.MODEL.contains("google_sdk") ||
               Build.MODEL.contains("Emulator") ||
               Build.MODEL.contains("Android SDK") ||
               Build.MANUFACTURER.contains("Genymotion") ||
               (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
               "google_sdk".equals(Build.PRODUCT)
    }

    fun isFridaDetected(): Boolean {
        if (!NativeChecker.isLoaded()) return false
        return NativeChecker.checkMaps() || NativeChecker.checkThreads()
    }

    fun isXposedDetected(): Boolean {
        return try {
            Class.forName("de.robv.android.xposed.XposedBridge")
            true
        } catch (_: ClassNotFoundException) { false }
    }

    fun checkAll(context: Context): List<String> {
        NativeChecker.load()

        val warnings = mutableListOf<String>()
        if (isDebugged()) warnings.add("DEBUG")
        if (isEmulator()) warnings.add("EMULATOR")
        if (isFridaDetected()) warnings.add("FRIDA")
        if (isXposedDetected()) warnings.add("XPOSED")
        return warnings
    }

    private fun checkTracerPidJava(): Boolean {
        return try {
            val buf = java.io.BufferedReader(java.io.FileReader("/proc/self/status"))
            var line: String?
            while (buf.readLine().also { line = it } != null) {
                if (line!!.startsWith("TracerPid:")) {
                    return line!!.substring(10).trim().toInt() != 0
                }
            }
            false
        } catch (_: Exception) { false }
    }
}
