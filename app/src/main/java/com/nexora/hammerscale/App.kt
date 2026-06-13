package com.nexora.hammerscale

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Custom Application class for HammerScale
 * Performs comprehensive security checks before app launch
 */
class App : android.app.Application() {

    companion object {
        private const val TAG = "HammerScaleApp"
        
        @Volatile
        lateinit var instance: App
            private set
        
        // Store detected threats for reporting
        var detectedThreats: List<String> = emptyList()
            private set
        
        var securityCheckPassed: Boolean = false
            private set
        
        // Anti-tampering flag
        const val ANTI_TAMPER_KEY = "app_integrity_v1"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        Log.d(TAG, "=== HammerScale Application Starting ===")
        Log.d(TAG, "App Version: ${getAppVersion()}")
        Log.d(TAG, "Android Version: ${Build.VERSION.SDK_INT}")
        Log.d(TAG, "Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        
        // Perform security checks BEFORE any other initialization
        performStartupSecurityChecks()
    }

    private fun performStartupSecurityChecks() {
        val startTime = System.currentTimeMillis()
        
        try {
            // Run comprehensive security check
            Log.d(TAG, "Running comprehensive security check...")
            val isCompromised = SecurityModule.isDeviceCompromised(this)
            
            // Get detailed threat list
            detectedThreats = SecurityModule.getDetectedThreats(this)
            
            if (isCompromised) {
                Log.e(TAG, "=== SECURITY BREACH DETECTED ===")
                detectedThreats.forEach { threat ->
                    Log.e(TAG, "  - $threat")
                }
                securityCheckPassed = false
                handleSecurityBreach()
                return
            }
            
            // Additional runtime checks
            if (!performAdditionalChecks()) {
                Log.e(TAG, "=== ADDITIONAL SECURITY CHECK FAILED ===")
                securityCheckPassed = false
                handleSecurityBreach()
                return
            }
            
            // Check for APK tampering using native code if available
            if (SecurityModule.nativeCheckAll()) {
                Log.e(TAG, "=== NATIVE SECURITY CHECK FAILED ===")
                securityCheckPassed = false
                handleSecurityBreach()
                return
            }
            
            // All checks passed
            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "=== ALL SECURITY CHECKS PASSED (${elapsed}ms) ===")
            securityCheckPassed = true
            
        } catch (e: Exception) {
            Log.e(TAG, "Security check crashed: ${e.message}", e)
            // On error, be paranoid and block the app
            securityCheckPassed = false
            handleSecurityBreach()
        }
    }

    private fun performAdditionalChecks(): Boolean {
        // Check 1: Read-only system partition modification
        if (isSystemPartitionModified()) {
            Log.e(TAG, "System partition appears modified")
            return false
        }
        
        // Check 2: Debug flags
        if (isDebuggable()) {
            Log.e(TAG, "App is running in debuggable mode")
            return false
        }
        
        // Check 3: Clock manipulation (if system time was changed while app was running)
        if (detectClockManipulation()) {
            Log.e(TAG, "Clock manipulation detected")
            return false
        }
        
        // Check 4: Check for suspicious processes
        if (hasSuspiciousProcesses()) {
            Log.e(TAG, "Suspicious processes detected")
            return false
        }
        
        // Check 5: Check for hook frameworks in running processes
        if (isHookFrameworkActive()) {
            Log.e(TAG, "Hook framework is active")
            return false
        }
        
        return true
    }

    private fun isSystemPartitionModified(): Boolean {
        // Check if /system is mounted as rw (should be ro)
        return try {
            val mounts = File("/proc/mounts").readText()
            mounts.lines().any { line ->
                (line.contains("/system") || line.contains("/data")) &&
                line.contains("rw,") &&
                !line.contains("overlay")
            }
        } catch (e: Exception) { false }
    }

    private fun isDebuggable(): Boolean {
        // In release builds, this should be false
        return (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun detectClockManipulation(): Boolean {
        // Check if elapsed time is negative (clock set backward)
        val bootTime = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
        val realTime = System.currentTimeMillis()
        
        // If boot time is more than 24 hours in the future, clock was manipulated
        return bootTime - realTime > 24 * 60 * 60 * 1000
    }

    private fun hasSuspiciousProcesses(): Boolean {
        val suspiciousProcessNames = listOf(
            "frida-server",
            "frida",
            "gdb",
            "gdbserver",
            "lldb",
            "lldb-server",
            "tool-ui",
            "gameguardian",
            "gamecih"
        )
        
        return try {
            val processesDir = File("/proc")
            processesDir.listFiles()?.forEach { pidDir ->
                if (pidDir.isDirectory && pidDir.name.toIntOrNull() != null) {
                    try {
                        val cmdline = File("${pidDir.absolutePath}/cmdline").readText()
                        if (suspiciousProcessNames.any { cmdline.contains(it) }) {
                            Log.w(TAG, "Suspicious process found: $cmdline")
                        }
                    } catch (e: Exception) { }
                }
            }
            false // Don't block, just log - too many false positives
        } catch (e: Exception) { false }
    }

    private fun isHookFrameworkActive(): Boolean {
        return try {
            // Check if any known hook libraries are loaded in our process
            val maps = File("/proc/self/maps").readText()
            val hookIndicators = listOf(
                "xposed", "substrate", "frida", "libsubstrate", "libxposed",
                "libhook", "libdetour", "libinline", "libart-compiler"
            )
            hookIndicators.any { maps.contains(it) }
        } catch (e: Exception) { false }
    }

    private fun handleSecurityBreach() {
        Log.e(TAG, "!!! APP SECURITY BREACH !!!")
        Log.e(TAG, "Blocking app from running")
        
        // Save breach report
        saveBreachReport()
        
        // Force kill the app after a short delay
        Thread {
            Thread.sleep(100) // Short delay for logging
            android.os.Process.killProcess(android.os.Process.myPid())
        }.start()
    }

    private fun saveBreachReport() {
        try {
            val reportDir = File(cacheDir, "security_reports")
            reportDir.mkdirs()
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val reportFile = File(reportDir, "breach_$timestamp.txt")
            
            val report = buildString {
                appendLine("=== HammerScale Security Breach Report ===")
                appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                appendLine("App Version: ${getAppVersion()}")
                appendLine("Android Version: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Build: ${Build.DISPLAY}")
                appendLine()
                appendLine("Detected Threats:")
                detectedThreats.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("Stack Trace:")
                Log.getStackTraceString(Exception("Breach detected"))
            }
            
            reportFile.writeText(report)
            Log.d(TAG, "Breach report saved to: ${reportFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save breach report", e)
        }
    }

    private fun getAppVersion(): String {
        return try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            "${pi.versionName} (${pi.versionCode})"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * Check if the app should be allowed to run
     * Call this from critical entry points
     */
    fun isAllowedToRun(): Boolean {
        return securityCheckPassed && detectedThreats.isEmpty()
    }
}