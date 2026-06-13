/**
 * Magisk Detection Utilities
 * Advanced Magisk-specific detection methods
 */

#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <sys/system_properties.h>

#define LOG_TAG "MagiskDetect"

// Check for Magisk specific properties
static bool check_magisk_properties() {
    char value[PROP_VALUE_MAX];
    
    // Check various Magisk-related properties
    const char* props[] = {
        "persist.magisk.disable",
        "ro.magisk.disable",
        "magisk.version"
    };
    
    for (const char* prop : props) {
        if (__system_property_get(prop, value) > 0) {
            if (strlen(value) > 0) {
                return true;
            }
        }
    }
    
    return false;
}

// Check for Magisk modules
static bool check_magisk_modules() {
    const char* module_paths[] = {
        "/data/adb/modules",
        "/data/adb/magisk/modules"
    };
    
    for (const char* path : module_paths) {
        // If the modules directory exists and has content,
        // Magisk modules might be installed
        DIR* dir = opendir(path);
        if (dir) {
            // Check if directory has content
            struct dirent* entry;
            bool has_modules = false;
            while ((entry = readdir(dir)) != nullptr) {
                if (entry->d_name[0] != '.') {
                    has_modules = true;
                    break;
                }
            }
            closedir(dir);
            if (has_modules) {
                return true;
            }
        }
    }
    
    return false;
}

// Check for Magisk hide status
static bool check_magisk_hide() {
    // Magisk hide hides the app from certain detection methods
    // Check if our process is being hidden
    
    // This would typically check for specific Magisk hide mechanisms
    return false;
}

// Additional Magisk detection
extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_nexora_hammerscale_SecurityModule_checkMagiskModules(JNIEnv* env, jobject obj) {
    return check_magisk_modules() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"