#include <jni.h>
#include <android/log.h>
#include <sys/ptrace.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdio.h>
#include <string.h>
#include <dirent.h>
#include <stdlib.h>

#define LOG_TAG "NativeCheck"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

JNIEXPORT jboolean JNICALL
Java_com_vpnproxy_app_NativeChecker_ptraceMe(JNIEnv *env, jclass clazz) {
    if (ptrace(PTRACE_TRACEME, 0, 0, 0) < 0) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_vpnproxy_app_NativeChecker_checkTracerPid(JNIEnv *env, jclass clazz) {
    FILE *fp = fopen("/proc/self/status", "r");
    if (!fp) return JNI_FALSE;

    char line[256];
    while (fgets(line, sizeof(line), fp)) {
        if (strncmp(line, "TracerPid:", 10) == 0) {
            int pid = atoi(line + 10);
            fclose(fp);
            return pid != 0 ? JNI_TRUE : JNI_FALSE;
        }
    }
    fclose(fp);
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_vpnproxy_app_NativeChecker_checkMaps(JNIEnv *env, jclass clazz) {
    FILE *fp = fopen("/proc/self/maps", "r");
    if (!fp) return JNI_FALSE;

    char line[512];
    const char *patterns[] = {"frida", "gum", "gadget", "linjector", NULL};

    while (fgets(line, sizeof(line), fp)) {
        for (int i = 0; patterns[i]; i++) {
            if (strstr(line, patterns[i])) {
                fclose(fp);
                return JNI_TRUE;
            }
        }
    }
    fclose(fp);
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_vpnproxy_app_NativeChecker_checkThreads(JNIEnv *env, jclass clazz) {
    DIR *dir = opendir("/proc/self/task");
    if (!dir) return JNI_FALSE;

    struct dirent *entry;
    const char *suspicious[] = {"gum-js-loop", "gmain", "pool-frida", NULL};

    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_type != DT_DIR) continue;

        char path[256];
        snprintf(path, sizeof(path), "/proc/self/task/%s/comm", entry->d_name);

        FILE *fp = fopen(path, "r");
        if (!fp) continue;

        char comm[64];
        if (fgets(comm, sizeof(comm), fp)) {
            size_t len = strlen(comm);
            if (len > 0 && comm[len - 1] == '\n') comm[len - 1] = '\0';

            for (int i = 0; suspicious[i]; i++) {
                if (strcmp(comm, suspicious[i]) == 0) {
                    fclose(fp);
                    closedir(dir);
                    return JNI_TRUE;
                }
            }
        }
        fclose(fp);
    }
    closedir(dir);
    return JNI_FALSE;
}
