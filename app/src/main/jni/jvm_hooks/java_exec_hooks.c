//
// Created by maks on 05.01.2025.
//

#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/*
 * POSIX basename() and dirname() may mutate their input and can return
 * static storage, so they are not safe if forkAndExec runs on two threads.
 * Byte arrays from JNI are not necessarily NUL-terminated. Path splitting
 * below only reads a private copy. Environment blocks keep every original
 * NUL-delimited entry and only replace or append LD_LIBRARY_PATH and PATH.
 */

static char *dup_bytes(const char *bytes, size_t len) {
    if (len == SIZE_MAX || (bytes == NULL && len != 0)) {
        return NULL;
    }
    char *copy = malloc(len + 1);
    if (copy == NULL) {
        return NULL;
    }
    if (len > 0) {
        memcpy(copy, bytes, len);
    }
    copy[len] = '\0';
    return copy;
}

static char *dup_cstr(const char *text) {
    if (text == NULL) {
        return NULL;
    }
    return dup_bytes(text, strlen(text));
}

/* Copy [bytes, bytes + len) and return a malloc'd final path component. Does not read past len. */
static char *exec_hooks_copy_basename(const char *bytes, size_t len) {
    char *copy = dup_bytes(bytes, len);
    char saved[2];
    const char *base;
    char *result;
    size_t n;
    size_t end;
    size_t start;

    if (copy == NULL) {
        return NULL;
    }
    n = strlen(copy);
    end = n;
    while (end > 0 && copy[end - 1] == '/') {
        end--;
    }
    if (end == 0) {
        saved[0] = n > 0 ? '/' : '.';
        saved[1] = '\0';
        base = saved;
    } else {
        start = end;
        while (start > 0 && copy[start - 1] != '/') {
            start--;
        }
        copy[end] = '\0';
        base = copy + start;
    }
    result = dup_cstr(base);
    free(copy);
    return result;
}

/* Private copy of dirname(). The caller's string, including getenv(), is left unchanged. */
static char *exec_hooks_ffmpeg_directory(const char *ffmpeg_path) {
    char *copy = dup_cstr(ffmpeg_path);
    char *slash;
    char *result;
    size_t n;

    if (copy == NULL) {
        return NULL;
    }
    n = strlen(copy);
    while (n > 1 && copy[n - 1] == '/') {
        copy[--n] = '\0';
    }
    slash = strrchr(copy, '/');
    if (slash == NULL) {
        result = dup_cstr(".");
    } else if (slash == copy) {
        slash[1] = '\0';
        result = dup_cstr(copy);
    } else {
        *slash = '\0';
        result = dup_cstr(copy);
    }
    free(copy);
    return result;
}

static int entry_is_assignment(const char *entry, size_t entry_len, const char *key) {
    size_t key_len = strlen(key);
    if (entry_len < key_len + 1) {
        return 0;
    }
    return memcmp(entry, key, key_len) == 0 && entry[key_len] == '=';
}

static int env_emit(char *dst, size_t dst_cap, size_t *used, int writing, const char *data, size_t len) {
    if (len > SIZE_MAX - *used) {
        return -1;
    }
    if (writing) {
        if (*used > dst_cap || len > dst_cap - *used) {
            return -1;
        }
        if (len > 0) {
            memcpy(dst + *used, data, len);
        }
    }
    *used += len;
    return 0;
}

static int env_emit_nul(char *dst, size_t dst_cap, size_t *used, int *count, int writing) {
    char nul = '\0';
    if (*count == INT_MAX || env_emit(dst, dst_cap, used, writing, &nul, 1) != 0) {
        return -1;
    }
    (*count)++;
    return 0;
}

static int env_emit_assignment(char *dst, size_t dst_cap, size_t *used, int *count, int writing,
                               const char *key, const char *value) {
    if (env_emit(dst, dst_cap, used, writing, key, strlen(key)) != 0 ||
        env_emit(dst, dst_cap, used, writing, "=", 1) != 0 ||
        env_emit(dst, dst_cap, used, writing, value, strlen(value)) != 0 ||
        env_emit_nul(dst, dst_cap, used, count, writing) != 0) {
        return -1;
    }
    return 0;
}

static int env_pass(const char *src, size_t src_len, const char *directory,
                    char *dst, size_t dst_cap, size_t *used, int *count, int writing) {
    size_t index = 0;
    int saw_ld_library_path = 0;
    int saw_path = 0;

    while (index < src_len) {
        size_t start = index;
        while (index < src_len && src[index] != '\0') {
            index++;
        }
        size_t entry_len = index - start;
        const char *entry = src + start;
        if (index < src_len) {
            index++;
        }

        if (entry_is_assignment(entry, entry_len, "LD_LIBRARY_PATH")) {
            saw_ld_library_path = 1;
            if (env_emit_assignment(dst, dst_cap, used, count, writing, "LD_LIBRARY_PATH", directory) != 0) {
                return -1;
            }
        } else if (entry_is_assignment(entry, entry_len, "PATH")) {
            saw_path = 1;
            if (env_emit_assignment(dst, dst_cap, used, count, writing, "PATH", directory) != 0) {
                return -1;
            }
        } else if (env_emit(dst, dst_cap, used, writing, entry, entry_len) != 0 ||
                   env_emit_nul(dst, dst_cap, used, count, writing) != 0) {
            return -1;
        }
    }

    if (!saw_ld_library_path &&
        env_emit_assignment(dst, dst_cap, used, count, writing, "LD_LIBRARY_PATH", directory) != 0) {
        return -1;
    }
    if (!saw_path &&
        env_emit_assignment(dst, dst_cap, used, count, writing, "PATH", directory) != 0) {
        return -1;
    }
    return 0;
}

/*
 * dst == NULL measures the required size and does not write.
 * A short buffer fails without writing. *out_len is the byte count actually
 * needed, including each entry's terminating NUL.
 */
static int exec_hooks_rebuild_env(const char *src, size_t src_len, const char *directory,
                                  char *dst, size_t dst_cap, size_t *out_len, int *out_count) {
    size_t used = 0;
    int count = 0;

    if (directory == NULL || out_len == NULL || out_count == NULL || (src == NULL && src_len != 0)) {
        return -1;
    }
    if ((src_len > 0 && env_pass(src, src_len, directory, NULL, 0, &used, &count, 0) != 0) ||
        (src_len == 0 && env_pass(NULL, 0, directory, NULL, 0, &used, &count, 0) != 0)) {
        return -1;
    }
    *out_len = used;
    *out_count = count;
    if (dst == NULL) {
        return 0;
    }
    if (dst_cap < used) {
        return -1;
    }

    used = 0;
    count = 0;
    if (env_pass(src_len == 0 ? NULL : src, src_len, directory, dst, dst_cap, &used, &count, 1) != 0 ||
        used != *out_len || count != *out_count) {
        return -1;
    }
    return 0;
}

#ifndef JAVA_EXEC_HOOKS_STRING_UNIT

#include "jvm_hooks.h"
#include <dlfcn.h>

#include "environ/environ.h"
#include "utils.h"

static jint (*orig_ProcessImpl_forkAndExec)(JNIEnv *env, jobject process, jint mode, jbyteArray helperpath, jbyteArray prog, jbyteArray argBlock, jint argc, jbyteArray envBlock, jint envc, jbyteArray dir, jintArray std_fds, jboolean redirectErrorStream);

static jint fail_exec(JNIEnv *env, const char *reason) {
    printf("exec_hooks: %s\n", reason);
    if ((*env)->ExceptionCheck(env)) {
        return -1;
    }
    jclass io_exception = (*env)->FindClass(env, "java/io/IOException");
    if (io_exception == NULL || (*env)->ExceptionCheck(env)) {
        return -1;
    }
    (*env)->ThrowNew(env, io_exception, reason);
    (*env)->DeleteLocalRef(env, io_exception);
    return -1;
}

static void hook_exec_fail(JNIEnv *env, const char *reason) {
    printf("%s\n", reason);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
    }
    (*env)->ExceptionClear(env);
    orig_ProcessImpl_forkAndExec = NULL;
}

// Turn a C-style string into a Java byte array. Returns NULL without touching
// a half-initialized array when allocation fails.
static jbyteArray stringToBytes(JNIEnv *env, const char *string) {
    size_t len;
    jsize array_len;
    jbyteArray result;

    if (string == NULL) {
        return NULL;
    }
    len = strlen(string);
    if (len == SIZE_MAX || len + 1 > (size_t)INT_MAX) {
        return NULL;
    }
    array_len = (jsize)(len + 1);
    result = (*env)->NewByteArray(env, array_len);
    if (result == NULL || (*env)->ExceptionCheck(env)) {
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, result, 0, array_len, (const jbyte *)string);
    if ((*env)->ExceptionCheck(env)) {
        return NULL;
    }
    return result;
}

static int copy_prog_basename(JNIEnv *env, jbyteArray prog, char **out_basename) {
    jsize len;
    jbyte *bytes = NULL;
    char *base;

    *out_basename = NULL;
    if (prog == NULL) {
        return 0;
    }
    len = (*env)->GetArrayLength(env, prog);
    if ((*env)->ExceptionCheck(env) || len < 0) {
        return -1;
    }
    if (len > 0) {
        bytes = (*env)->GetByteArrayElements(env, prog, NULL);
        if (bytes == NULL) {
            return -1;
        }
    }
    base = exec_hooks_copy_basename(bytes == NULL ? "" : (const char *)bytes, (size_t)len);
    if (bytes != NULL) {
        (*env)->ReleaseByteArrayElements(env, prog, bytes, JNI_ABORT);
    }
    if (base == NULL) {
        return -1;
    }
    *out_basename = base;
    return 0;
}

/* ProcessEnvironment.environ snapshots the native process environment, unlike
 * System.getenv's cached map (which can predate the launcher's setenv calls).
 * Use the JVM's snapshot instead of traversing a concurrently mutable environ. */
static jbyteArray inherited_env_array(JNIEnv *env) {
    jclass environment = (*env)->FindClass(env, "java/lang/ProcessEnvironment");
    if (environment == NULL) return NULL;
    jmethodID environ_method = (*env)->GetStaticMethodID(env, environment, "environ", "()[[B");
    if (environ_method == NULL) {
        (*env)->DeleteLocalRef(env, environment);
        return NULL;
    }
    jobjectArray entries = (*env)->CallStaticObjectMethod(env, environment, environ_method);
    (*env)->DeleteLocalRef(env, environment);
    if ((*env)->ExceptionCheck(env) || entries == NULL) return NULL;
    jsize size = (*env)->GetArrayLength(env, entries);
    if ((*env)->ExceptionCheck(env) || size % 2 != 0) {
        (*env)->DeleteLocalRef(env, entries);
        return NULL;
    }
    size_t length = 0;
    /* entries contains alternating name and value byte arrays. */
    for (jsize i = 0; i < size; i++) {
        jbyteArray entry = (*env)->GetObjectArrayElement(env, entries, i);
        if (entry == NULL || (*env)->ExceptionCheck(env)) {
            (*env)->DeleteLocalRef(env, entries);
            return NULL;
        }
        jsize part_length = (*env)->GetArrayLength(env, entry);
        (*env)->DeleteLocalRef(env, entry);
        if ((*env)->ExceptionCheck(env) || (size_t)part_length + 1 > (size_t)INT_MAX - length) {
            (*env)->DeleteLocalRef(env, entries);
            return NULL;
        }
        length += (size_t)part_length + 1;
    }
    jbyteArray array = (*env)->NewByteArray(env, (jsize)length);
    char* snapshot = malloc(length == 0 ? 1 : length);
    if (array == NULL || snapshot == NULL) {
        free(snapshot);
        if (array != NULL) (*env)->DeleteLocalRef(env, array);
        (*env)->DeleteLocalRef(env, entries);
        return NULL;
    }
    size_t offset = 0;
    for (jsize i = 0; i < size; i++) {
        jbyteArray entry = (*env)->GetObjectArrayElement(env, entries, i);
        if (entry == NULL || (*env)->ExceptionCheck(env)) goto snapshot_failed;
        jsize part_length = (*env)->GetArrayLength(env, entry);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->DeleteLocalRef(env, entry);
            goto snapshot_failed;
        }
        (*env)->GetByteArrayRegion(env, entry, 0, part_length, (jbyte*)snapshot + offset);
        (*env)->DeleteLocalRef(env, entry);
        if ((*env)->ExceptionCheck(env)) goto snapshot_failed;
        offset += (size_t)part_length;
        snapshot[offset++] = (i % 2 == 0) ? '=' : '\0';
    }
    (*env)->SetByteArrayRegion(env, array, 0, (jsize)length, (const jbyte*)snapshot);
snapshot_failed:
    free(snapshot);
    (*env)->DeleteLocalRef(env, entries);
    if ((*env)->ExceptionCheck(env) || offset != length) {
        (*env)->DeleteLocalRef(env, array);
        return NULL;
    }
    return array;
}

static int rebuild_env_array(JNIEnv *env, jbyteArray envBlock, const char *directory,
                             jbyteArray *out_block, jint *out_count) {
    jbyte *elements = NULL;
    const char *src = NULL;
    size_t src_len = 0;
    size_t need = 0;
    size_t wrote = 0;
    int count = 0;
    int wrote_count = 0;
    char *buffer;
    jbyteArray array;

    *out_block = NULL;
    /* NULL means inherit, not an explicitly empty environment block. */
    if (envBlock == NULL) {
        jbyteArray inherited = inherited_env_array(env);
        if (inherited == NULL) return -1;
        int result = rebuild_env_array(env, inherited, directory, out_block, out_count);
        (*env)->DeleteLocalRef(env, inherited);
        return result;
    }
    if (envBlock != NULL) {
        jsize len = (*env)->GetArrayLength(env, envBlock);
        if ((*env)->ExceptionCheck(env) || len < 0) {
            return -1;
        }
        src_len = (size_t)len;
        if (len > 0) {
            elements = (*env)->GetByteArrayElements(env, envBlock, NULL);
            if (elements == NULL) {
                return -1;
            }
            src = (const char *)elements;
        }
    }

    if (exec_hooks_rebuild_env(src, src_len, directory, NULL, 0, &need, &count) != 0 ||
        need > (size_t)INT_MAX) {
        if (elements != NULL) {
            (*env)->ReleaseByteArrayElements(env, envBlock, elements, JNI_ABORT);
        }
        return -1;
    }

    buffer = malloc(need);
    if (buffer == NULL ||
        exec_hooks_rebuild_env(src, src_len, directory, buffer, need, &wrote, &wrote_count) != 0 ||
        wrote != need || wrote_count != count) {
        free(buffer);
        if (elements != NULL) {
            (*env)->ReleaseByteArrayElements(env, envBlock, elements, JNI_ABORT);
        }
        return -1;
    }
    if (elements != NULL) {
        (*env)->ReleaseByteArrayElements(env, envBlock, elements, JNI_ABORT);
    }

    array = (*env)->NewByteArray(env, (jsize)wrote);
    if (array == NULL || (*env)->ExceptionCheck(env)) {
        free(buffer);
        return -1;
    }
    (*env)->SetByteArrayRegion(env, array, 0, (jsize)wrote, (const jbyte *)buffer);
    free(buffer);
    if ((*env)->ExceptionCheck(env)) {
        return -1;
    }
    *out_block = array;
    *out_count = wrote_count;
    return 0;
}

/**
 * Hooked version of java.lang.UNIXProcess.forkAndExec()
 * which is used to handle the "open" command and "ffmpeg" invocations
 */
static jint hooked_ProcessImpl_forkAndExec(JNIEnv *env, jobject process, jint mode, jbyteArray helperpath, jbyteArray prog, jbyteArray argBlock, jint argc, jbyteArray envBlock, jint envc, jbyteArray dir, jintArray std_fds, jboolean redirectErrorStream) {
    char *base = NULL;

    if (copy_prog_basename(env, prog, &base) != 0) {
        return fail_exec(env, "failed to read executable path");
    }

    if (base != NULL && strcmp(base, "xdg-open") == 0) {
        free(base);
        Java_org_lwjgl_glfw_CallbackBridge_nativeClipboard(env, NULL, CLIPBOARD_OPEN, argBlock);
        return 0;
    }

    if (base != NULL && strcmp(base, "ffmpeg") == 0) {
        /* Copy immediately. setenv() on another thread may invalidate getenv(). */
        char *ffmpeg_path = dup_cstr(getenv("POJAV_FFMPEG_PATH"));
        free(base);
        if (ffmpeg_path != NULL) {
            char *directory = exec_hooks_ffmpeg_directory(ffmpeg_path);
            jbyteArray new_env = NULL;
            jint new_envc = 0;
            jbyteArray new_prog;

            if (directory == NULL) {
                free(ffmpeg_path);
                return fail_exec(env, "failed to resolve ffmpeg directory");
            }
            if (rebuild_env_array(env, envBlock, directory, &new_env, &new_envc) != 0) {
                free(directory);
                free(ffmpeg_path);
                return fail_exec(env, "failed to rebuild environment block");
            }
            free(directory);
            new_prog = stringToBytes(env, ffmpeg_path);
            free(ffmpeg_path);
            if (new_prog == NULL || (*env)->ExceptionCheck(env)) {
                return fail_exec(env, "failed to encode ffmpeg path");
            }
            prog = new_prog;
            envBlock = new_env;
            envc = new_envc;
        }
    } else {
        free(base);
    }

    if (orig_ProcessImpl_forkAndExec == NULL) {
        return fail_exec(env, "forkAndExec hook is not installed");
    }
    return orig_ProcessImpl_forkAndExec(env, process, mode, helperpath, prog, argBlock, argc, envBlock, envc, dir, std_fds, redirectErrorStream);
}

// Hook the forkAndExec method in the Java runtime for custom executable overriding.
void hookExec(JNIEnv *env) {
    const char *class_name = NULL;
    void *symbol = dlsym(RTLD_DEFAULT, "Java_java_lang_UNIXProcess_forkAndExec");

    if (symbol != NULL) {
        class_name = "java/lang/UNIXProcess";
    } else {
        symbol = dlsym(RTLD_DEFAULT, "Java_java_lang_ProcessImpl_forkAndExec");
        if (symbol != NULL) {
            class_name = "java/lang/ProcessImpl";
        }
    }
    if (symbol == NULL || class_name == NULL) {
        const char *error = dlerror();
        char reason[256];
        snprintf(reason, sizeof(reason),
                 "exec_hooks: forkAndExec symbol not found (%s); leaving the Java method unchanged",
                 error != NULL ? error : "dlsym returned NULL");
        hook_exec_fail(env, reason);
        return;
    }

    jclass hookClass = (*env)->FindClass(env, class_name);
    if (hookClass == NULL || (*env)->ExceptionCheck(env)) {
        char reason[256];
        snprintf(reason, sizeof(reason),
                 "exec_hooks: FindClass(%s) failed; leaving the Java method unchanged",
                 class_name);
        hook_exec_fail(env, reason);
        if (hookClass != NULL) {
            (*env)->DeleteLocalRef(env, hookClass);
        }
        return;
    }

    JNINativeMethod methods[] = {
            {"forkAndExec", "(I[B[B[BI[BI[B[IZ)I", (void *)&hooked_ProcessImpl_forkAndExec}
    };
    orig_ProcessImpl_forkAndExec =
            (jint (*)(JNIEnv *, jobject, jint, jbyteArray, jbyteArray, jbyteArray, jint, jbyteArray, jint, jbyteArray, jintArray, jboolean))symbol;
    if ((*env)->RegisterNatives(env, hookClass, methods, 1) != 0) {
        hook_exec_fail(env, "exec_hooks: RegisterNatives(forkAndExec) failed; leaving the Java method unchanged");
        (*env)->DeleteLocalRef(env, hookClass);
        return;
    }
    (*env)->DeleteLocalRef(env, hookClass);
    printf("Registered forkAndExec\n");
}

#endif
