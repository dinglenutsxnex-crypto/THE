# HammerScale Security Implementation

## Overview

This document describes the security hardening implemented in HammerScale to prevent reverse engineering, tampering, and unauthorized modifications.

## Security Features Implemented

### 1. Anti-Root Detection
- **Magisk Detection**: Checks for Magisk manager app, module paths, su binaries, and mount points
- **SuperSU Detection**: Detects SuperSU installation
- **KingRoot Detection**: Identifies KingRoot and similar root apps
- **Generic Root Detection**: Scans for su binaries in common locations

### 2. Anti-Frida Detection
- **Port Scanning**: Checks `/proc/net/tcp` for Frida's default ports (27042, 27043)
- **File Detection**: Scans for Frida server binaries and agents
- **Process Maps**: Checks `/proc/self/maps` for Frida-related libraries
- **Named Pipes**: Detects Frida's named pipe communication
- **Command Line**: Scans `/proc/self/cmdline` for Frida processes
- **TracerPid**: Detects if another process is tracing the app

### 3. Anti-Emulator Detection
- **System Files**: Checks for emulator-specific files (`libc_qemu.so`, etc.)
- **System Properties**: Validates emulator-related build properties
- **CPU Info**: Scans `/proc/cpuinfo` for emulator keywords
- **Hardware Analysis**: Detects goldfish, ranchu, and other emulator hardware

### 4. Anti-Debugging
- **Debugger Detection**: Checks `Debug.isDebuggerConnected()`
- **Tracer Detection**: Monitors `/proc/self/status` for TracerPid
- **Tracing Detection**: Identifies if the app is being traced

### 5. Anti-Hook Detection
- **Xposed Detection**: Checks for Xposed framework installation
- **LSPosed Detection**: Identifies LSPosed modules
- **Substrate Detection**: Detects Cydia Substrate
- **Library Scanning**: Scans loaded libraries for hook indicators

### 6. Anti-Tampering
- **APK Integrity**: Verifies package integrity (signatures, first install time)
- **Backup Disabled**: App data cannot be backed up
- **Cleartext Disabled**: Network traffic must use HTTPS
- **Certificate Pinning**: Network requests are pinned to expected certificates

### 7. Native Detection (C++)
- **Advanced Frida Detection**: Native-level Frida detection
- **Magisk Detection**: Native Magisk presence detection
- **Combined Mask**: Returns bitmask of all detected threats

## How It Works

### Startup Flow
```
App Launch
    │
    ▼
App.onCreate()
    │
    ▼
SecurityModule.performFullSecurityCheck()
    │
    ├──► Check Root (files, packages, Magisk)
    ├──► Check Frida (ports, files, maps, pipes)
    ├──► Check Emulator (files, props, CPU)
    ├──► Check Debugger (isDebuggerConnected, TracerPid)
    ├──► Check Hooks (Xposed, LSPosed, Substrate)
    ├──► Check APK Integrity (signatures)
    ├──► Native Checks (C++ security_detector.cpp)
    │
    ▼
Any threat detected?
    │
    ├──► YES → Log breach, save report, kill app
    │
    └──► NO → Start integrity monitoring, continue
```

### Blocking Behavior

When a threat is detected:
1. The threat is logged with detailed information
2. A breach report is saved to the app's cache directory
3. The app is immediately terminated
4. The user sees nothing (app just closes)

## GitHub Actions Integration

### Build Workflow Features

The CI/CD pipeline includes:

1. **Security Scanning**: Trivy vulnerability scanner
2. **Secret Detection**: Prevents accidental secret exposure
3. **APK Integrity Verification**: SHA256 hash generation
4. **Signature Verification**: Validates APK signing
5. **SBOM Generation**: Software Bill of Materials
6. **Dependency Scanning**: OWASP Dependency Check
7. **Build Attestation**: Sigstore integration for releases

### Required Secrets

To enable signed builds, configure these GitHub secrets:

| Secret Name | Description |
|-------------|-------------|
| `KEYSTORE_BASE64` | Base64-encoded keystore file |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias in keystore |
| `KEY_PASSWORD` | Key password |

## Setup Instructions

### 1. Generate Release Keystore

```bash
# Generate a new keystore
keytool -genkey -v -keystore release.keystore \
  -alias your_alias -keyalg RSA -keysize 2048 -validity 10000

# Encode to base64 for GitHub
base64 -w 0 release.keystore > keystore_base64.txt
```

### 2. Configure GitHub Secrets

1. Go to your GitHub repository → Settings → Secrets and variables → Actions
2. Add each secret:
   - `KEYSTORE_BASE64`: Paste contents of `keystore_base64.txt`
   - `KEYSTORE_PASSWORD`: Your keystore password
   - `KEY_ALIAS`: Your key alias
   - `KEY_PASSWORD`: Your key password

### 3. Configure Certificate Pinning

Edit `app/src/main/res/xml/network_security_config.xml`:

```xml
<pin-set expiration="2025-12-31">
    <!-- Get your server's certificate hash -->
    <pin digest="SHA-256">YOUR_BASE64_ENCODED_HASH</pin>
    <!-- Backup pin for rotation -->
    <pin digest="SHA-256">YOUR_BACKUP_HASH</pin>
</pin-set>
```

To get the certificate hash:
```bash
openssl s_client -servername your-server.com -connect your-server.com:443 \
  </dev/null 2>/dev/null | openssl x509 -fingerprint -sha256 -noout
```

### 4. Build Release APK

```bash
# Clean and build
./gradlew clean assembleRelease

# The APK will be at:
# app/build/outputs/apk/release/app-release.apk
```

## Testing the Security

### Test on Rooted Device
1. Install Magisk and root your test device
2. Install the release APK
3. The app should immediately close without any error message

### Test on Emulator
1. Start an Android emulator
2. Install the release APK
3. The app should close immediately

### Test with Frida
1. Install Frida server on a rooted device
2. Start Frida: `frida-server -p 0`
3. Install the release APK
4. The app should close immediately

### View Breach Reports
```bash
# On a rooted device, check for breach reports
adb shell
su
cat /data/data/com.nexora.hammerscale/cache/security_reports/breach_*.txt
```

## Troubleshooting

### App Closes Immediately
This is **expected behavior** if a security threat is detected. To debug:

1. Check `adb logcat` for "SecurityModule" and "SecurityNative" tags
2. Review the breach report in the app's cache directory
3. Temporarily disable checks in debug builds to test

### Build Fails - Native Libraries
Make sure NDK is installed:
```bash
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "ndk;25.2.9519653"
```

### Keystore Issues
- Ensure the keystore password, alias, and key password are correct
- The keystore file must be Base64 encoded when stored as a GitHub secret
- Use `keytool -list -v -keystore release.keystore` to verify contents

## Security Recommendations

1. **Never disable security checks in release builds**
2. **Regularly update detection patterns** as new tools emerge
3. **Monitor breach reports** to understand attack patterns
4. **Keep the native library** - it's harder to analyze than Java code
5. **Use ProGuard/R8 minification** - it makes reverse engineering harder
6. **Implement SSL pinning** - prevents MITM attacks
7. **Use Android Keystore** for any sensitive data storage

## License

This security implementation is for the HammerScale project. Do not redistribute.