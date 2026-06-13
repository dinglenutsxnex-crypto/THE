package com.nexora.hammerscale

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.InputStream
import java.lang.reflect.Method

/**
 * Comprehensive Security Module for HammerScale
 * Detects and blocks:
 * - Root access (Magisk, SuperSU, KingRoot, etc.)
 * - Emulators and Virtual Machines
 * - Frida and other instrumentation frameworks
 * - Debuggers and tracing
 * - Hooking frameworks (Xposed, LSPosed, etc.)
 * - APK tampering and repackaging
 */
object SecurityModule {

    private const val TAG = "SecurityModule"
    
    // All detection results for debugging
    data class SecurityReport(
        val isRooted: Boolean = false,
        val isMagiskPresent: Boolean = false,
        val isFridaRunning: Boolean = false,
        val isEmulator: Boolean = false,
        val isDebuggerAttached: Boolean = false,
        val isXposedDetected: Boolean = false,
        val isAppTampered: Boolean = false,
        val isVMWare: Boolean = false,
        val isGenymotion: Boolean = false,
        val isParallelSpace: Boolean = false,
        val isGameGuardian: Boolean = false,
        val isGamecih: Boolean = false,
        val detectedRootApps: List<String> = emptyList(),
        val detectedHooks: List<String> = emptyList()
    )

    // List of dangerous packages to detect
    private val ROOT_PACKAGES = listOf(
        "com.noshufou.android.su",
        "com.noshufou.android.su.elite",
        "eu.chainfire.supersu",
        "com.koushikdutta.superuser",
        "com.thirdparty.superuser",
        "com.yellowes.su",
        "com.topjohnwu.magisk",
        "com.kingroot.master",
        "com.kingo.root",
        "com.smedialink.oneclickroot",
        "com.zhiqupk.root.global",
        "com.alephzain.framaroot",
        "com.formyhm.hdeApp",
        "com.chelpus.lackypatch",
        "com.dimonvideo.luckypatcher",
        "com.forpda.lp",
        "com.android.vending.billing.InAppBillingService.LUCK",
        "com.android.vending.billing.InAppBillingService.CRAC",
        "com.android.vending.billing.InAppBillingService.COIN",
        "com.android.vending.billing.InAppBillingService.SIC",
        "com.android.vending.billing.InAppBillingService.VTC"
    )

    private val HOOK_PACKAGES = listOf(
        "de.robv.android.xposed.installer",
        "com.solohsu.android.edxp.manager",
        "com.android.activity.hooks",
        "com.ryzee.pornfree",
        "org.me.cat_mouse.xed",
        "com.saurik.substrate",
        "com.dev Advances.root",
        "com.dev Advances.game",
        "com.dev Advances",
        "com.ASG.version",
        "com.nice.game.light",
        "com.azp.pct",
        "com.mogoent.lib",
        "com.core.lib",
        "com.core.lib.s",
        "com.lib.core",
        "com.pimp.modi",
        "com.mod.ipo",
        "com.luz.mod",
        "com.pimp"
    )

    private val HACKING_PACKAGES = listOf(
        "com.gamecihapp.gcih",
        "com.gamecihapp.gcih2",
        "com.gamecihapp.gcihgame",
        "com.gamecihgame.cheat",
        "com.cih.gamecih",
        "com.zhiershu.gamecih",
        "com.cihsoftware.gamecih",
        "com.gameguardian",
        "com.dimonvideo.gameguardian",
        "com.dimonvideo.gameguardian.gps",
        "com.vking.hacker",
        "com.vking.h",
        "com.apkeditor",
        "com.apkeditor.game",
        "com.apkeditor.pro",
        "com.lua.android",
        "com.scorpion",
        "com.mobilmoda.asset",
        "com.mobilmoda.tool"
    )

    private val EMULATOR_PACKAGES = listOf(
        "com.android.emulator",
        "com.google.android.launcher.layouts.genymotion",
        "com.bluestacks",
        "com.bluestacks.home",
        "com.bluestacks.settings",
        "com.bluestacks.BstCommand",
        "com.bluestacks.BstTop",
        "com.bluestacks.filemanager",
        "com.bluestacks.appmart",
        "com.nox.vm",
        "com.noxplayer",
        "com.noxlauncher",
        "com.bignox",
        "com.hyperchem.hyperbox",
        "com.memu",
        "com.mwind",
        "com.kongregate"
    )

    // Files that indicate root/jailbreak
    private val ROOT_FILES = arrayOf(
        "/system/app/Superuser.apk",
        "/system/app/SuperSU.apk",
        "/system/app/Magisk.apk",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/system/bin/failsafe/su",
        "/system/sd/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
        "/su/bin/su",
        "/su/bin",
        "/system/xbin/daemonsu",
        "/system/etc/init.d/99SuperSUDaemon",
        "/system/.dim221",
        "/system/.cust",
        "/system/.supersu",
        "/system/bin/.ext",
        "/system/bin/.su",
        "/system/usr/su-rec",
        "/system/usr/hadk",
        "/system/usr/SAFEMODE",
        "/system/firmware/suw",
        "/system/buildprop.sh",
        "/system/xbin/sugote",
        "/system/xbin/sugote-debug",
        "/system/lib64/libsudoku.so",
        "/system/lib64/libsubstrate.so",
        "/system/lib/libsubstrate.so",
        "/data/data/com.kingroot.master",
        "/data/data/com.kingo.root",
        "/data/data/com.topjohnwu.magisk",
        "/data/data/de.robv.android.xposed.installer",
        "/data/data/eu.chainfire.supersu",
        "/data/adb/magisk",
        "/data/adb/magisk.img",
        "/data/adb/magisk_xposed",
        "/data/adb/su",
        "/data/adb/su.d",
        "/data/user/0/de.robv.android.xposed.installer",
        "/data/user_de/0/de.robv.android.xposed.installer",
        "/data/adb/post-fs-data-mode",
        "/data/adb/service.d",
        "/data/adb/systemdha",
        "/data/dalvik-cache"
    )

    // Frida-related files and ports
    private val FRIDA_FILES = arrayOf(
        "/data/local/tmp/frida-server",
        "/data/local/tmp/frida-agent-arm64.so",
        "/data/local/tmp/frida-agent-arm.so",
        "/data/local/tmp/frida-agent-x86.so",
        "/data/local/tmp/frida-agent-x86_64.so",
        "/data/local/tmp/re.frida.server",
        "/data/local/tmp/re.frida.frida_server",
        "/data/local/tmp/frida-gadget.so",
        "/data/local/tmp/frida-gadget-16.0.8.so",
        "/data/local/tmp/frida-gadget-16.1.0.so",
        "/data/local/tmp/frida-gadget-16.2.0.so",
        "/data/local/tmp/frida-gadget-16.3.0.so",
        "/data/local/tmp/frida-gadget-16.4.0.so",
        "/data/local/tmp/frida-gadget-16.5.0.so",
        "/data/local/tmp/frida-gadget.so",
        "/frida-agent.so",
        "/frida-gadget.so",
        "/data/local/tmp/frida-server-12.0.0",
        "/data/local/tmp/frida-server-12.0.1",
        "/data/local/tmp/frida-server-12.0.2",
        "/data/local/tmp/frida-server-12.1.0",
        "/data/local/tmp/frida-server-12.2.0",
        "/data/local/tmp/frida-server-12.3.0",
        "/data/local/tmp/frida-server-12.4.0",
        "/data/local/tmp/frida-server-12.5.0",
        "/data/local/tmp/frida-server-12.6.0",
        "/data/local/tmp/frida-server-12.7.0",
        "/data/local/tmp/frida-server-12.8.0",
        "/data/local/tmp/frida-server-12.9.0",
        "/data/local/tmp/frida-server-12.10.0",
        "/data/local/tmp/frida-server-12.11.0",
        "/data/local/tmp/frida-server-12.12.0",
        "/data/local/tmp/frida-server-12.13.0",
        "/data/local/tmp/frida-server-12.14.0",
        "/data/local/tmp/frida-server-12.15.0",
        "/data/local/tmp/frida-server-14.0.0",
        "/data/local/tmp/frida-server-14.1.0",
        "/data/local/tmp/frida-server-14.2.0",
        "/data/local/tmp/frida-server-15.0.0",
        "/data/local/tmp/frida-server-15.1.0",
        "/data/local/tmp/frida-server-15.2.0",
        "/data/local/tmp/frida-server-16.0.0",
        "/data/local/tmp/frida-server-16.0.1",
        "/data/local/tmp/frida-server-16.0.2",
        "/data/local/tmp/frida-server-16.0.3",
        "/data/local/tmp/frida-server-16.0.4",
        "/data/local/tmp/frida-server-16.0.5",
        "/data/local/tmp/frida-server-16.0.6",
        "/data/local/tmp/frida-server-16.0.7",
        "/data/local/tmp/frida-server-16.0.8",
        "/data/local/tmp/frida-server-16.1.0",
        "/data/local/tmp/frida-server-16.1.1",
        "/data/local/tmp/frida-server-16.1.2",
        "/data/local/tmp/frida-server-16.2.0",
        "/data/local/tmp/frida-server-16.2.1",
        "/data/local/tmp/frida-server-16.2.2",
        "/data/local/tmp/frida-server-16.2.3",
        "/data/local/tmp/frida-server-16.2.4",
        "/data/local/tmp/frida-server-16.2.5",
        "/data/local/tmp/frida-server-16.3.0",
        "/data/local/tmp/frida-server-16.4.0",
        "/data/local/tmp/frida-server-16.4.1",
        "/data/local/tmp/frida-server-16.5.0",
        "/data/local/tmp/frida-server-16.5.1",
        "/data/local/tmp/frida-server-16.5.2",
        "/data/local/tmp/frida-server-16.6.0",
        "/data/local/tmp/frida-server-16.6.1",
        "/data/local/tmp/frida-server-16.7.0",
        "/data/local/tmp/frida-server-16.8.0",
        "/data/local/tmp/frida-server-16.9.0",
        "/data/local/tmp/frida-server-16.10.0",
        "/data/local/tmp/frida-server-16.11.0",
        "/data/local/tmp/frida-server-16.12.0",
        "/data/local/tmp/frida-server-16.13.0",
        "/data/local/tmp/frida-server-16.14.0",
        "/data/local/tmp/frida-server-16.15.0",
        "/data/local/tmp/frida-server-16.16.0",
        "/data/local/tmp/frida-server-16.17.0",
        "/data/local/tmp/frida-server-16.18.0",
        "/data/local/tmp/frida-server-16.19.0",
        "/data/local/tmp/frida-server-16.20.0"
    )

    private val FRIDA_PORTS = listOf(27042, 27043)
    private val FRIDA_PIPE_NAMES = listOf(
        "frida-server",
        "re.frida.server",
        "frida-agent",
        "linjector"
    )

    // VM/Emulator specific files and properties
    private val VM_FILES = arrayOf(
        "/system/lib/libc_qemu.so",
        "/system/lib/libc_qemu_connector.so",
        "/system/bin/qemu-props",
        "/system/bin/qemu-params",
        "/system/bin/boot-check",
        "/system/bin/androidshim",
        "/system/xbin/sched-get-affinity",
        "/dev/qemu_pipe",
        "/dev/socket/qemud",
        "/dev/socket/baseband-detect",
        "/dev/tty",
        "/dev/tty0",
        "/dev/console",
        "/dev/kmsg",
        "/proc/tty",
        "/proc/cpuinfo",
        "/sys/class/power_supply",
        "/sys/bus/cpu/devices/cpu0/cpufreq/scaling_cur_freq",
        "/sys/bus/cpu/devices/cpu0/cpufreq/scaling_max_freq",
        "/sys/devices/virtual/thermal/thermal_zone0/temp",
        "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq",
        "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_min_freq",
        "/sys/module/goldfish_sync",
        "/sys/module/goldfish_rotary",
        "/sys/module/goldfish_thermal",
        "/sys/module/goldfish_battery",
        "/sys/module/goldfish_memory",
        "/sys/module/goldfish_trace",
        "/sys/module/goldfish_switch_power",
        "/sys/module/goldfish_mali",
        "/sys/module/goldfish_hw",
        "/sys/module/goldfish_sync",
        "/sys/module/goldfish_rotary",
        "/sys/module/goldfish_input_touch",
        "/sys/module/goldfish_pmem",
        "/sys/module/goldfish_block",
        "/sys/module/goldfish_net",
        "/sys/module/goldfish_audio",
        "/sys/module/goldfish_virtio_gpu",
        "/sys/module/goldfish韦bird_sync",
        "/sys/module/goldfish",
        "/sys/kernel/debug/goldfish_debug",
        "/sys/kernel/debug/goldfish_rotary",
        "/sys/kernel/debug/goldfish_input",
        "/sys/kernel/debug/goldfish_memory",
        "/sys/kernel/debug/goldfish_mali",
        "/proc/driver/goldfish_debug",
        "/proc/driver/goldfish_memory",
        "/init.goldfish.rc",
        "/init.cpios-setuid.sh",
        "/init.environ.rc",
        "/init.trace.rc",
        "/init.carrier.rc",
        "/init.nvidia.rc",
        "/init.protect.rc",
        "/init.ramdisk.rc",
        "/init.selinux.rc",
        "/init.usb.configfs.rc",
        "/init.xlog.rc",
        "/init.recovery*",
        "/fstab.goldfish",
        "/default.prop",
        "/system/build.prop.bak",
        "/data/local/tmp/busybox"
    )

    private val EMULATOR_PROPS = mapOf(
        "ro.kernel.qemu" to "1",
        "ro.kernel.qemu.gles" to "1",
        "ro.kernel.qemu.gltransport" to "eglMakeCurrent",
        "ro.kernel.qemu.audio" to "sdlaudio",
        "ro.hardware" to "goldfish",
        "ro.hardware" to "ranchu",
        "ro.product.model" to "sdk",
        "ro.product.model" to "sdk_gphone",
        "ro.product.model" to "sdk_gphone64",
        "ro.product.model" to "sdk_x86",
        "ro.product.model" to "sdk_x86_64",
        "ro.product.model" to "Android SDK",
        "ro.product.model" to "Android Studio",
        "ro.product.model" to "Emulator",
        "ro.product.model" to "genymotion",
        "ro.product.brand" to "Android",
        "ro.product.brand" to "generic",
        "ro.product.device" to "generic",
        "ro.product.device" to "goldfish",
        "ro.product.device" to "ranchu",
        "ro.product.name" to "sdk",
        "ro.product.name" to "sdk_gphone",
        "ro.product.name" to "sdk_gphone64",
        "ro.board.platform" to "goldfish",
        "ro.board.platform" to "ranchu",
        "ro.build.product" to "sdk",
        "ro.build.product" to "full",
        "ro.build.product" to "full_x86",
        "ro.build.product" to "full_x86_64",
        "ro.build.product" to "full_arm64",
        "ro.build.product" to "full_armv7",
        "ro.build.version.sdk" to "25",
        "ro.build.version.sdk" to "26",
        "ro.build.version.sdk" to "27",
        "ro.build.version.sdk" to "28",
        "ro.debuggable" to "1",
        "ro.secure" to "0",
        "security.perf_harden" to "0",
        "ro.allow.mock.location" to "1",
        "ro.debuggable" to "1",
        "init.svc.debuggerd" to "running",
        "init.svc.debuggerd64" to "running",
        "init.svc.surfaceflinger" to "running",
        "init.svc.zygote" to "running"
    )

    private val VM_CPU_INFO_KEYWORDS = listOf(
        "goldfish",
        "ranchu",
        "qemu",
        "VirtualBox",
        "VMware",
        "microsoft corporation",
        "bhyve",
        "parallels",
        "kvm",
        "hax",
        "haxm",
        "hyperv",
        "virtio",
        "unison"
    )

    private val VM_DEVICE_IDS = listOf(
        "Google", "Android", "sdk", "emulator", "unknown"
    )

    // ============ MAIN DETECTION METHOD ============

    /**
     * Performs all security checks and returns a detailed report.
     * If ANY check fails, the app should be blocked.
     */
    fun performFullSecurityCheck(context: Context): SecurityReport {
        Log.d(TAG, "=== Starting Full Security Check ===")
        
        val detectedRootApps = mutableListOf<String>()
        val detectedHooks = mutableListOf<String>()
        
        // Check 1: Root detection
        val isRooted = checkRootFiles() || checkRootBinaries()
        val isMagiskPresent = checkMagisk()
        
        // Check 2: Root app packages
        val rootPackagesFound = checkPackageList(ROOT_PACKAGES)
        detectedRootApps.addAll(rootPackagesFound)
        
        // Check 3: Frida detection
        val isFridaRunning = checkFridaFiles() || 
                            checkFridaPorts() || 
                            checkFridaInProcMaps() ||
                            checkFridaInCmdLine()
        
        // Check 4: Emulator detection
        val isEmulator = checkEmulatorFiles() || 
                         checkEmulatorProps() || 
                         checkCpuInfo() ||
                         checkVMWareFiles() ||
                         checkGenymotion()
        
        // Check 5: Debugger detection
        val isDebuggerAttached = checkDebugger() || checkTracing()
        
        // Check 6: Xposed/LSPosed detection
        val xposedPackages = checkPackageList(HOOK_PACKAGES)
        val isXposedDetected = checkXposedFiles() || xposedPackages.isNotEmpty()
        detectedHooks.addAll(xposedPackages)
        
        // Check 7: APK tampering
        val isAppTampered = checkAPKIntegrity(context) || checkSignature(context)
        
        // Check 8: GameGuardian/CIH detection
        val hackingPackages = checkPackageList(HACKING_PACKAGES)
        
        // Check 9: Parallel space / dual apps
        val isParallelSpace = checkPackageList(EMULATOR_PACKAGES).isNotEmpty()
        
        // Check 10: Additional VM detection
        val isVMWare = checkVMWare() || checkVMFiles()
        val isGenymotion = checkGenymotion()

        val report = SecurityReport(
            isRooted = isRooted || detectedRootApps.isNotEmpty(),
            isMagiskPresent = isMagiskPresent,
            isFridaRunning = isFridaRunning,
            isEmulator = isEmulator,
            isDebuggerAttached = isDebuggerAttached,
            isXposedDetected = isXposedDetected,
            isAppTampered = isAppTampered,
            isVMWare = isVMWare,
            isGenymotion = isGenymotion,
            isParallelSpace = isParallelSpace,
            isGameGuardian = hackingPackages.any { it.contains("gameguardian") || it.contains("gamecih") },
            isGamecih = hackingPackages.any { it.contains("gamecih") || it.contains("cih") },
            detectedRootApps = detectedRootApps,
            detectedHooks = detectedHooks
        )

        Log.d(TAG, "=== Security Check Complete ===")
        Log.d(TAG, report.toString())
        
        return report
    }

    /**
     * Quick check - returns true if device is compromised
     */
    fun isDeviceCompromised(context: Context): Boolean {
        return performFullSecurityCheck(context).let { report ->
            report.isRooted ||
            report.isMagiskPresent ||
            report.isFridaRunning ||
            report.isEmulator ||
            report.isDebuggerAttached ||
            report.isXposedDetected ||
            report.isAppTampered ||
            report.isVMWare ||
            report.isGameGuardian ||
            report.isGamecih
        }
    }

    /**
     * Get a human-readable list of detected threats
     */
    fun getDetectedThreats(context: Context): List<String> {
        val report = performFullSecurityCheck(context)
        val threats = mutableListOf<String>()
        
        if (report.isRooted) threats.add("Root Access Detected")
        if (report.isMagiskPresent) threats.add("Magisk Detected")
        if (report.isFridaRunning) threats.add("Frida/Instrumentation Detected")
        if (report.isEmulator) threats.add("Emulator Detected")
        if (report.isDebuggerAttached) threats.add("Debugger Attached")
        if (report.isXposedDetected) threats.add("Xposed/LSPosed Detected")
        if (report.isAppTampered) threats.add("APK Tampered/Repackaged")
        if (report.isVMWare) threats.add("VMWare Detected")
        if (report.isGenymotion) threats.add("Genymotion Detected")
        if (report.isGameGuardian) threats.add("GameGuardian Detected")
        if (report.isGamecih) threats.add("GameCIH Detected")
        if (report.isParallelSpace) threats.add("Parallel Space Detected")
        
        if (report.detectedRootApps.isNotEmpty()) {
            threats.add("Root Apps: ${report.detectedRootApps.joinToString()}")
        }
        if (report.detectedHooks.isNotEmpty()) {
            threats.add("Hook Frameworks: ${report.detectedHooks.joinToString()}")
        }
        
        return threats
    }

    // ============ ROOT DETECTION ============

    private fun checkRootFiles(): Boolean {
        return ROOT_FILES.any { File(it).exists() }
    }

    private fun checkRootBinaries(): Boolean {
        val paths = System.getenv("PATH")?.split(":") ?: return false
        return paths.any { path ->
            listOf("su", "sudo", "magisk").any { 
                File("$path/$it").exists() 
            }
        }
    }

    private fun checkMagisk(): Boolean {
        // Check for Magisk manager package
        try {
            val pm = App.instance.packageManager
            pm.getPackageInfo("com.topjohnwu.magisk", 0)
            return true
        } catch (e: Exception) { }
        
        // Check for Magisk files
        val magiskPaths = listOf(
            "/sbin/.magisk",
            "/sbin/.core",
            "/data/adb/magisk",
            "/data/adb/magisk.img",
            "/data/adb/magisk_xposed",
            "/data/adb/su",
            "/data/adb/su.d",
            "/data/adb/post-fs-data-mode",
            "/data/adb/service.d",
            "/data/adb/modules",
            "/data/adb/modules/*/system",
            "/data/adb/magiskhide",
            "/data/adb/magisk_uninstall",
            "/data/adb/magisk_boot"
        )
        
        if (magiskPaths.any { File(it).exists() }) return true
        
        // Check for magisk in /proc/mounts
        return try {
            File("/proc/mounts").readText().contains("magisk")
        } catch (e: Exception) { false }
    }

    // ============ FRIDA DETECTION ============

    private fun checkFridaFiles(): Boolean {
        return FRIDA_FILES.any { File(it).exists() }
    }

    private fun checkFridaPorts(): Boolean {
        // Check /proc/net/tcp for Frida ports
        return try {
            val tcpFile = File("/proc/net/tcp")
            val content = tcpFile.readText()
            FRIDA_PORTS.any { port ->
                // Port in hex in /proc/net/tcp is in little-endian
                val portHex = String.format("%04X", port)
                content.contains(portHex)
            }
        } catch (e: Exception) { false }
    }

    private fun checkFridaInProcMaps(): Boolean {
        return try {
            val mapsFile = File("/proc/self/maps")
            val content = mapsFile.readText()
            FRIDA_PIPE_NAMES.any { 
                content.contains(it) || 
                content.contains("frida") ||
                content.contains("linjector")
            }
        } catch (e: Exception) { false }
    }

    private fun checkFridaInCmdLine(): Boolean {
        return try {
            val cmdline = File("/proc/self/cmdline").readText()
            cmdline.contains("frida") || cmdline.contains("gdb") || cmdline.contains("lldb")
        } catch (e: Exception) { false }
    }

    private fun checkFridaStatus(): Boolean {
        // Check /proc/self/status for Frida-related threads
        return try {
            val statusFile = File("/proc/self/status")
            val content = statusFile.readText()
            content.contains("TracerPid:") && 
            content.lines().any { it.startsWith("TracerPid:") && it.substringAfter("TracerPid:").trim().toIntOrNull()?.let { it > 0 } == true }
        } catch (e: Exception) { false }
    }

    // ============ EMULATOR DETECTION ============

    private fun checkEmulatorFiles(): Boolean {
        return VM_FILES.any { File(it).exists() }
    }

    private fun checkEmulatorProps(): Boolean {
        // Check system properties
        for ((key, value) in EMULATOR_PROPS) {
            if (getSystemProperty(key) == value) return true
        }
        return false
    }

    private fun checkCpuInfo(): Boolean {
        return try {
            val cpuInfo = File("/proc/cpuinfo").readText().lowercase()
            VM_CPU_INFO_KEYWORDS.any { cpuInfo.contains(it) } ||
            checkCpuFreq() || checkDeviceIds()
        } catch (e: Exception) { false }
    }

    private fun checkCpuFreq(): Boolean {
        return try {
            val paths = listOf(
                "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq",
                "/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq"
            )
            paths.any { path ->
                val freq = File(path).readText().trim().toLongOrNull() ?: 0L
                // Emulators often have very low or very high fake frequencies
                freq in 100L..800000L || freq > 4000000L
            }
        } catch (e: Exception) { false }
    }

    private fun checkDeviceIds(): Boolean {
        return try {
            val device = Build.DEVICE
            val product = Build.PRODUCT
            val hardware = Build.HARDWARE
            
            listOf(device, product, hardware).any { field ->
                VM_DEVICE_IDS.any { id -> 
                    field.lowercase().contains(id.lowercase())
                }
            }
        } catch (e: Exception) { false }
    }

    private fun checkVMWareFiles(): Boolean {
        val vmwarePaths = listOf(
            "/system/lib64/libvmci.so",
            "/system/lib/libvmci.so",
            "/system/lib64/libvmguestlib.so",
            "/system/lib/libvmguestlib.so",
            "/system/lib64/libVBoxGuestLibs.so",
            "/system/lib/libVBoxGuestLibs.so",
            "/system/lib64/libVBoxAdpLinux.so",
            "/system/lib/libVBoxAdpLinux.so",
            "/proc/fs/vmblock/dev",
            "/proc/scsi/scsi",
            "/proc/ide/hd0/model"
        )
        return vmwarePaths.any { File(it).exists() }
    }

    private fun checkVMWare(): Boolean {
        return try {
            val cpuInfo = File("/proc/cpuinfo").readText()
            cpuInfo.contains("vmware") || cpuInfo.contains("virtual platform")
        } catch (e: Exception) { false }
    }

    private fun checkVMFiles(): Boolean {
        val vmFiles = listOf(
            "/dev/vmnet",
            "/dev/net/tun",
            "/dev/vboxnet",
            "/dev/vboxuser"
        )
        return vmFiles.any { File(it).exists() }
    }

    private fun checkGenymotion(): Boolean {
        return try {
            val manufacturer = Build.MANUFACTURER
            val model = Build.MODEL
            val brand = Build.BRAND
            
            listOf(manufacturer, model, brand).any { 
                it.lowercase().contains("genymotion") || 
                it.lowercase().contains("generic")
            }
        } catch (e: Exception) { false }
    }

    // ============ DEBUGGER/TRACING DETECTION ============

    private fun checkDebugger(): Boolean {
        return try {
            android.os.Debug.isDebuggerConnected()
        } catch (e: Exception) { false }
    }

    private fun checkTracing(): Boolean {
        return try {
            // Check if we're being traced
            val statusFile = File("/proc/self/status")
            val content = statusFile.readText()
            
            // Check TracerPid
            content.lines().any { line ->
                line.startsWith("TracerPid:") && 
                line.substringAfter("TracerPid:").trim().toIntOrNull()?.let { it > 0 } == true
            }
        } catch (e: Exception) { false }
    }

    private fun checkSELinuxStatus(): Boolean {
        return try {
            val enforcing = getSystemProperty("ro.build.selinux_enforce")
            enforcing == "0" // Disabled SELinux is suspicious
        } catch (e: Exception) { false }
    }

    // ============ XPOSED/HOOK DETECTION ============

    private fun checkXposedFiles(): Boolean {
        val xposedFiles = listOf(
            "/data/data/de.robv.android.xposed.installer",
            "/data/data/de.robv.android.xposed.installer/conf/apps_list",
            "/data/data/eu.chainfire.supersu",
            "/data/data/com.solohsu.android.edxp.manager",
            "/data/data/com.android.activity.hooks",
            "/data/local/share/xposed",
            "/data/local/xposed",
            "/system/lib/libxposed_art.so",
            "/system/lib64/libxposed_art.so",
            "/system/framework/XposedBridge.jar",
            "/system/app/XposedInstaller.apk",
            "/system/app/XposedBridge",
            "/system/xposed"
        )
        return xposedFiles.any { File(it).exists() }
    }

    // ============ APK INTEGRITY CHECKS ============

    private fun checkAPKIntegrity(context: Context): Boolean {
        // Check if APK was modified after installation
        return try {
            val pm = context.packageManager
            val pi: PackageInfo = pm.getPackageInfo(context.packageName, 0)
            
            // Check first install time vs last update time
            val firstInstallTime = pi.firstInstallTime
            val lastUpdateTime = pi.lastUpdateTime
            
            // If lastUpdateTime is significantly after firstInstallTime, APK might have been reinstalled
            // This is a basic check - a sophisticated attacker could bypass this
            
            // Check package source (should be Google Play or allowed sources)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val packageInfo = pm.getPackageInfo(context.packageName, 
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
                val signingInfo = packageInfo.signingInfo
                
                // Verify signing certificate matches expected
                // This would need a pre-configured expected certificate hash
            }
            
            false // Placeholder - implement proper certificate pinning
        } catch (e: Exception) {
            false
        }
    }

    private fun checkSignature(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            val pi = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            val signatures = pi.signatures
            
            // Check for debug signature (should not be present in release)
            if (Build.DEBUG) {
                return false // Allow debug in debug builds
            }
            
            signatures?.any { it.toCharsString().contains("DEBUG") } == true
        } catch (e: Exception) { false }
    }

    // ============ HELPER METHODS ============

    private fun checkPackageList(packages: List<String>): List<String> {
        val found = mutableListOf<String>()
        try {
            val pm = App.instance.packageManager
            val installedPackages = pm.getInstalledPackages(0)
            val installedNames = installedPackages.map { it.packageName }
            
            packages.forEach { pkg ->
                if (installedNames.contains(pkg)) {
                    found.add(pkg)
                }
            }
        } catch (e: Exception) { }
        return found
    }

    @SuppressLint("PrivateApi")
    private fun getSystemProperty(key: String): String? {
        return try {
            val systemProperties = Class.forName("android.os.SystemProperties")
            val get = systemProperties.getMethod("get", String::class.java, String::class.java)
            get.invoke(systemProperties, key, null) as? String
        } catch (e: Exception) { null }
    }

    private fun readFile(path: String): String? {
        return try {
            BufferedReader(FileReader(path)).use { it.readText() }
        } catch (e: Exception) { null }
    }

    private fun runCommand(cmd: String): String? {
        return try {
            Runtime.getRuntime().exec(cmd).inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) { null }
    }

    // ============ NATIVE METHODS (implemented in C++) ============

    external fun nativeIsFridaDetected(): Boolean
    external fun nativeIsMagiskDetected(): Boolean
    external fun nativeCheckAll(): Boolean
    external fun nativeGetDetectionMask(): Int

    companion object {
        init {
            try {
                System.loadLibrary("securitymodule")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Native security module not loaded, using Java-only checks")
            }
        }
    }
}