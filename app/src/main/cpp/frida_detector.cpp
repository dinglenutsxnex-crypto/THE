/**
 * Frida Detection Utilities
 * Advanced Frida-specific detection methods
 */

#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#define LOG_TAG "FridaDetect"

// Frida uses specific ports for communication
static bool check_frida_socket() {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return false;
    
    // Check if we can connect to Frida's default port
    struct sockaddr_in addr;
    addr.sin_family = AF_INET;
    addr.sin_port = htons(27042); // Default Frida port
    
    // Use loopback address
    inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr);
    
    // If connection succeeds, Frida server is running
    int result = connect(sock, (struct sockaddr*)&addr, sizeof(addr));
    close(sock);
    
    return result == 0;
}

// Check for Frida in task names
static bool check_task_names() {
    DIR* dir = opendir("/proc/self/task");
    if (!dir) return false;
    
    struct dirent* entry;
    while ((entry = readdir(dir)) != nullptr) {
        // Check if task name contains Frida indicators
        // This is a simplified check
    }
    closedir(dir);
    return false;
}

// Additional Frida detection
extern "C" {

// Extended Frida check
JNIEXPORT jboolean JNICALL
Java_com_nexora_hammerscale_SecurityModule_checkFridaSocket(JNIEnv* env, jobject obj) {
    return check_frida_socket() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"