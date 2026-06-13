/**
 * Security Module - Native Detection
 * Advanced detection of Frida, Magisk, emulators, and other security threats
 * 
 * This code is intentionally obfuscated to make reverse engineering difficult.
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <fcntl.h>
#include <dlfcn.h>

#define LOG_TAG "SecurityNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Obfuscation helper
static inline uint32_t obfuscate_uint(uint32_t val, uint32_t key) {
    return val ^ key;
}

static inline uint64_t obfuscate_ull(uint64_t val, uint64_t key) {
    return val ^ key;
}

// Detection masks
#define DETECT_FRIDA      0x01
#define DETECT_MAGISK     0x02
#define DETECT_EMULATOR   0x04
#define DETECT_HOOK       0x08
#define DETECT_DEBUGGER   0x10
#define DETECT_VM         0x20
#define DETECT_ROOT       0x40
#define DETECT_TAMPER     0x80

// Global detection state
static uint32_t g_detection_mask = 0;

// Frida detection constants
static const char* FRIDA_PORTS[] = {
    "6A 42",    // 27042 in hex (little endian)
    "6B 43",    // 27043
};

static const char* FRIDA_FILES[] = {
    "/data/local/tmp/frida-server",
    "/data/local/tmp/re.frida.server",
    "/data/local/tmp/frida-agent",
    "/frida-agent",
    "/frida-gadget",
};

// Magisk detection paths
static const char* MAGISK_PATHS[] = {
    "/sbin/.magisk",
    "/sbin/.core",
    "/data/adb/magisk",
    "/data/adb/magisk.img",
    "/data/adb/magisk_xposed",
    "/data/adb/su",
    "/data/adb/modules",
    "/data/adb/magiskhide",
    "/data/adb/magisk_uninstall",
    "/data/local/tmp/frida-server-16.2.5",
    "/data/local/tmp/frida-server-16.1.2",
};

// Emulator detection
static const char* EMULATOR_FILES[] = {
    "/system/lib/libc_qemu.so",
    "/system/bin/qemu-props",
    "/system/bin/qemu-params",
    "/dev/qemu_pipe",
    "/dev/socket/qemud",
};

// Suspicious library names in maps
static const char* HOOK_LIBS[] = {
    "frida",
    "xposed",
    "substrate",
    "linjector",
    "libsubstrate",
    "libxposed",
};

// ============ Helper Functions ============

static bool file_exists(const char* path) {
    struct stat buffer;
    return (stat(path, &buffer) == 0);
}

static bool dir_exists(const char* path) {
    struct stat buffer;
    return (stat(path, &buffer) == 0) && S_ISDIR(buffer.st_mode);
}

static std::string read_file(const char* path) {
    std::string content;
    FILE* f = fopen(path, "r");
    if (f) {
        char buf[4096];
        size_t n;
        while ((n = fread(buf, 1, sizeof(buf), f)) > 0) {
            content.append(buf, n);
        }
        fclose(f);
    }
    return content;
}

static bool contains_in_file(const char* path, const char* needle) {
    std::string content = read_file(path);
    return content.find(needle) != std::string::npos;
}

static bool check_proc_maps_for_pattern(const char* pattern) {
    return contains_in_file("/proc/self/maps", pattern);
}

// ============ Frida Detection ============

static bool detect_frida_ports() {
    // Check /proc/net/tcp for Frida listening ports
    std::string tcp_content = read_file("/proc/net/tcp");
    
    for (const char* port_hex : FRIDA_PORTS) {
        if (tcp_content.find(port_hex) != std::string::npos) {
            LOGE("[Frida] Detected port %s", port_hex);
            return true;
        }
    }
    return false;
}

static bool detect_frida_files() {
    for (const char* file : FRIDA_FILES) {
        if (file_exists(file)) {
            LOGE("[Frida] Found file: %s", file);
            return true;
        }
    }
    return false;
}

static bool detect_frida_in_maps() {
    for (const char* lib : HOOK_LIBS) {
        if (check_proc_maps_for_pattern(lib)) {
            LOGE("[Frida] Found in maps: %s", lib);
            return true;
        }
    }
    return false;
}

static bool detect_frida_named_pipes() {
    // Frida uses named pipes for communication
    DIR* dir = opendir("/data/local/tmp");
    if (dir) {
        struct dirent* entry;
        while ((entry = readdir(dir)) != nullptr) {
            std::string name(entry->d_name);
            if (name.find("frida") != std::string::npos || 
                name.find("re.frida") != std::string::npos) {
                closedir(dir);
                LOGE("[Frida] Found named pipe: %s", name.c_str());
                return true;
            }
        }
        closedir(dir);
    }
    return false;
}

static bool detect_frida_cmdline() {
    std::string cmdline = read_file("/proc/self/cmdline");
    if (cmdline.find("frida") != std::string::npos ||
        cmdline.find("gdb") != std::string::npos ||
        cmdline.find("lldb") != std::string::npos) {
        LOGE("[Frida] Found in cmdline");
        return true;
    }
    return false;
}

static bool detect_frida_status() {
    std::string status = read_file("/proc/self/status");
    // Look for TracerPid (tracer process)
    size_t pos = status.find("TracerPid:");
    if (pos != std::string::npos) {
        std::string value = status.substr(pos + 11);
        // Extract just the number
        size_t end = value.find_first_not_of(" \t");
        value = value.substr(0, end);
        if (!value.empty() && value[0] != '0') {
            LOGE("[Frida] TracerPid: %s", value.c_str());
            return true;
        }
    }
    return false;
}

static bool is_frida_detected() {
    bool detected = detect_frida_ports() ||
                    detect_frida_files() ||
                    detect_frida_in_maps() ||
                    detect_frida_named_pipes() ||
                    detect_frida_cmdline() ||
                    detect_frida_status();
    
    if (detected) {
        LOGE("[Frida] Frida instrumentation detected!");
    }
    return detected;
}

// ============ Magisk Detection ============

static bool detect_magisk_paths() {
    for (const char* path : MAGISK_PATHS) {
        if (file_exists(path) || dir_exists(path)) {
            LOGE("[Magisk] Found path: %s", path);
            return true;
        }
    }
    return false;
}

static bool detect_magisk_in_proc_mounts() {
    std::string mounts = read_file("/proc/mounts");
    if (mounts.find("magisk") != std::string::npos) {
        LOGE("[Magisk] Found in mounts");
        return true;
    }
    return false;
}

static bool detect_magisk_su() {
    // Check for su binary that belongs to Magisk
    std::string su_binary;
    const char* su_paths[] = {
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/local/su",
        "/data/adb/su",
    };
    
    for (const char* path : su_paths) {
        if (file_exists(path)) {
            // Check if it has Magisk's signature
            // Magisk su binaries are typically much larger than stock ones
            struct stat st;
            if (stat(path, &st) == 0) {
                // Magisk su is usually > 100KB, stock su is ~20-40KB
                if (st.st_size > 100 * 1024) {
                    LOGE("[Magisk] Large su binary found at %s", path);
                    return true;
                }
            }
        }
    }
    return false;
}

static bool detect_selinux_status() {
    // Check SELinux enforcement status
    // Magisk typically sets SELinux to permissive
    std::string enforce = read_file("/sys/fs/selinux/enforce");
    if (enforce.find("0") != std::string::npos) {
        LOGE("[Magisk] SELinux is permissive");
        return true;
    }
    return false;
}

static bool is_magisk_detected() {
    bool detected = detect_magisk_paths() ||
                    detect_magisk_in_proc_mounts() ||
                    detect_magisk_su() ||
                    detect_selinux_status();
    
    if (detected) {
        LOGE("[Magisk] Magisk/Hide detected!");
    }
    return detected;
}

// ============ Emulator Detection ============

static bool detect_emulator_files() {
    for (const char* file : EMULATOR_FILES) {
        if (file_exists(file)) {
            LOGE("[Emulator] Found: %s", file);
            return true;
        }
    }
    return false;
}

static bool detect_cpu_features() {
    // Read CPU info for emulator indicators
    std::string cpuinfo = read_file("/proc/cpuinfo");
    
    const char* emulator_strings[] = {
        "goldfish", "ranchu", "qemu", "virtualbox", "vmware",
        "bhyve", "parallels", "kvm", "hax", "hyperv"
    };
    
    for (const char* str : emulator_strings) {
        if (cpuinfo.find(str) != std::string::npos) {
            LOGE("[Emulator] CPU indicator: %s", str);
            return true;
        }
    }
    return false;
}

static bool is_emulator_detected() {
    bool detected = detect_emulator_files() || detect_cpu_features();
    
    if (detected) {
        LOGE("[Emulator] Virtual environment detected!");
    }
    return detected;
}

// ============ Hook Detection ============

static bool detect_hook_libs_in_maps() {
    // Check if any hooking libraries are loaded in our process
    for (const char* lib : HOOK_LIBS) {
        if (check_proc_maps_for_pattern(lib)) {
            LOGE("[Hook] Library detected: %s", lib);
            return true;
        }
    }
    return false;
}

static bool is_hook_detected() {
    bool detected = detect_hook_libs_in_maps();
    
    if (detected) {
        LOGE("[Hook] Hook framework detected!");
    }
    return detected;
}

// ============ Debugger Detection ============

static bool is_debugger_attached() {
    // Check if debugger is connected via /proc/self/status
    std::string status = read_file("/proc/self/status");
    
    size_t pos = status.find("TracerPid:");
    if (pos != std::string::npos) {
        std::string value = status.substr(pos + 11);
        size_t end = value.find_first_not_of(" \t");
        value = value.substr(0, end);
        if (!value.empty() && value[0] != '0') {
            LOGE("[Debugger] TracerPid not zero");
            return true;
        }
    }
    return false;
}

// ============ Root Detection ============

static bool is_root_detected() {
    // Check for common root indicators
    const char* root_paths[] = {
        "/system/app/Superuser.apk",
        "/system/app/SuperSU.apk",
        "/system/xbin/daemonsu",
        "/data/data/com.topjohnwu.magisk",
    };
    
    for (const char* path : root_paths) {
        if (file_exists(path)) {
            LOGE("[Root] Found: %s", path);
            return true;
        }
    }
    
    // Check for su binary
    const char* su_paths[] = {"/system/bin/su", "/system/xbin/su", "/sbin/su"};
    for (const char* path : su_paths) {
        if (file_exists(path)) {
            LOGE("[Root] Found su: %s", path);
            return true;
        }
    }
    
    return false;
}

// ============ VM Detection ============

static bool is_vm_detected() {
    // Check for VMware, VirtualBox, etc.
    const char* vm_files[] = {
        "/dev/vmnet",
        "/dev/vboxnet",
        "/system/lib64/libvmci.so",
        "/system/lib/libvmci.so",
    };
    
    for (const char* file : vm_files) {
        if (file_exists(file)) {
            LOGE("[VM] Found: %s", file);
            return true;
        }
    }
    
    return false;
}

// ============ Tamper Detection ============

static bool is_tamper_detected() {
    // This would check for APK modification
    // In a real implementation, you would verify:
    // 1. APK signature matches expected value
    // 2. APK is signed with release certificate (not debug)
    // 3. APK has not been patched/modified
    
    // For now, we just return false (would need proper implementation)
    return false;
}

// ============ Main Detection Function ============

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_nexora_hammerscale_SecurityModule_nativeIsFridaDetected(JNIEnv* env, jobject obj) {
    return is_frida_detected() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_nexora_hammerscale_SecurityModule_nativeIsMagiskDetected(JNIEnv* env, jobject obj) {
    return is_magisk_detected() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_nexora_hammerscale_SecurityModule_nativeCheckAll(JNIEnv* env, jobject obj) {
    // Reset detection mask
    g_detection_mask = 0;
    
    // Run all checks
    if (is_frida_detected()) g_detection_mask |= DETECT_FRIDA;
    if (is_magisk_detected()) g_detection_mask |= DETECT_MAGISK;
    if (is_emulator_detected()) g_detection_mask |= DETECT_EMULATOR;
    if (is_hook_detected()) g_detection_mask |= DETECT_HOOK;
    if (is_debugger_attached()) g_detection_mask |= DETECT_DEBUGGER;
    if (is_vm_detected()) g_detection_mask |= DETECT_VM;
    if (is_root_detected()) g_detection_mask |= DETECT_ROOT;
    if (is_tamper_detected()) g_detection_mask |= DETECT_TAMPER;
    
    // Log final result
    LOGI("Detection mask: 0x%08X", g_detection_mask);
    
    // Return true if ANY threat was detected
    return (g_detection_mask != 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_nexora_hammerscale_SecurityModule_nativeGetDetectionMask(JNIEnv* env, jobject obj) {
    return static_cast<jint>(g_detection_mask);
}

} // extern "C"