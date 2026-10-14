#include <jni.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "utils.h"

typedef int (*Main_Function_t)(int, char**);
typedef void (*android_update_LD_LIBRARY_PATH_t)(char*);

long shared_awt_surface;

static void free_copied_rows(char **rows, int count) {
	if (rows == NULL) {
		return;
	}
	for (int i = 0; i < count; i++) {
		free(rows[i]);
	}
	free(rows);
}

static char *copy_utf_chars(const char *src) {
	size_t len;
	char *copy;

	if (src == NULL) {
		return NULL;
	}
	len = strlen(src);
	copy = (char *) malloc(len + 1);
	if (copy == NULL) {
		return NULL;
	}
	memcpy(copy, src, len + 1);
	return copy;
}

char** convert_to_char_array(JNIEnv *env, jobjectArray jstringArray) {
	int num_rows;
	char **cArray;

	if (env == NULL || jstringArray == NULL) {
		return NULL;
	}
	num_rows = (*env)->GetArrayLength(env, jstringArray);
	if ((*env)->ExceptionCheck(env) || num_rows < 0) {
		return NULL;
	}
	/* One extra NULL slot so free_char_array can release the copies without JNI. */
	cArray = (char **) calloc((size_t) num_rows + 1, sizeof(char *));
	if (cArray == NULL) {
		return NULL;
	}

	for (int i = 0; i < num_rows; i++) {
		jstring row = (jstring) (*env)->GetObjectArrayElement(env, jstringArray, i);
		const char *utf;
		char *copy;

		if (row == NULL) {
			free_copied_rows(cArray, i);
			return NULL;
		}
		utf = (*env)->GetStringUTFChars(env, row, NULL);
		if (utf == NULL) {
			(*env)->DeleteLocalRef(env, row);
			free_copied_rows(cArray, i);
			return NULL;
		}
		copy = copy_utf_chars(utf);
		(*env)->ReleaseStringUTFChars(env, row, utf);
		(*env)->DeleteLocalRef(env, row);
		if (copy == NULL) {
			free_copied_rows(cArray, i);
			return NULL;
		}
		cArray[i] = copy;
	}

	return cArray;
}

jobjectArray convert_from_char_array(JNIEnv *env, char **charArray, int num_rows) {
	jclass stringClass;
	jobjectArray resultArr;

	if (env == NULL || num_rows < 0 || (charArray == NULL && num_rows > 0)) {
		return NULL;
	}
	stringClass = (*env)->FindClass(env, "java/lang/String");
	if (stringClass == NULL) {
		return NULL;
	}
	resultArr = (*env)->NewObjectArray(env, num_rows, stringClass, NULL);
	(*env)->DeleteLocalRef(env, stringClass);
	if (resultArr == NULL) {
		return NULL;
	}

	for (int i = 0; i < num_rows; i++) {
		jstring row = (*env)->NewStringUTF(env, charArray[i]);
		if (row == NULL) {
			(*env)->DeleteLocalRef(env, resultArr);
			return NULL;
		}
		(*env)->SetObjectArrayElement(env, resultArr, i, row);
		(*env)->DeleteLocalRef(env, row);
		if ((*env)->ExceptionCheck(env)) {
			(*env)->DeleteLocalRef(env, resultArr);
			return NULL;
		}
	}

	return resultArr;
}

void free_char_array(JNIEnv *env, jobjectArray jstringArray, const char **charArray) {
	(void) env;
	(void) jstringArray;

	if (charArray == NULL) {
		return;
	}
	/* Copies are independently owned and terminated by a NULL slot. */
	for (const char **row = charArray; *row != NULL; row++) {
		free((void *) *row);
	}
	free((void *) charArray);
}

jstring convertStringJVM(JNIEnv* srcEnv, JNIEnv* dstEnv, jstring srcStr) {
    if (srcStr == NULL) {
        return NULL;
    }
    
    const char* srcStrC = (*srcEnv)->GetStringUTFChars(srcEnv, srcStr, 0);
    jstring dstStr = (*dstEnv)->NewStringUTF(dstEnv, srcStrC);
	(*srcEnv)->ReleaseStringUTFChars(srcEnv, srcStr, srcStrC);
    return dstStr;
}

JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_utils_JREUtils_setupBridgeSurfaceAWT(JNIEnv *env, jclass clazz, jlong surface) {
	shared_awt_surface = surface;
}

JNIEXPORT jlong JNICALL Java_android_view_Surface_nativeGetBridgeSurfaceAWT(JNIEnv *env, jclass clazz) {
	return (jlong) shared_awt_surface;
}

JNIEXPORT jint JNICALL Java_android_os_OpenJDKNativeRegister_nativeRegisterNatives(JNIEnv *env, jclass clazz, jstring registerSymbol) {
	const char *register_symbol_c = (*env)->GetStringUTFChars(env, registerSymbol, 0);
	void *symbol = dlsym(RTLD_DEFAULT, register_symbol_c);
	if (symbol == NULL) {
		printf("dlsym %s failed: %s\n", register_symbol_c, dlerror());
		return -1;
	}
	
	int (*registerNativesForClass)(JNIEnv*) = symbol;
	int result = registerNativesForClass(env);
	(*env)->ReleaseStringUTFChars(env, registerSymbol, register_symbol_c);
	
	return (jint) result;
}

JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_utils_JREUtils_setLdLibraryPath(JNIEnv *env, jclass clazz, jstring ldLibraryPath) {
	// jclass exception_cls = (*env)->FindClass(env, "java/lang/UnsatisfiedLinkError");
	
	android_update_LD_LIBRARY_PATH_t android_update_LD_LIBRARY_PATH;
	
	void *libdl_handle = dlopen("libdl.so", RTLD_LAZY);
	void *updateLdLibPath = dlsym(libdl_handle, "android_update_LD_LIBRARY_PATH");
	if (updateLdLibPath == NULL) {
		updateLdLibPath = dlsym(libdl_handle, "__loader_android_update_LD_LIBRARY_PATH");
		if (updateLdLibPath == NULL) {
			char *dl_error_c = dlerror();
			;
			// (*env)->ThrowNew(env, exception_cls, dl_error_c);
		}
	}
	
	android_update_LD_LIBRARY_PATH = (android_update_LD_LIBRARY_PATH_t) updateLdLibPath;
	const char* ldLibPathUtf = (*env)->GetStringUTFChars(env, ldLibraryPath, 0);
	android_update_LD_LIBRARY_PATH(ldLibPathUtf);
	(*env)->ReleaseStringUTFChars(env, ldLibraryPath, ldLibPathUtf);
}

JNIEXPORT jboolean JNICALL Java_net_kdt_pojavlaunch_utils_JREUtils_dlopen(JNIEnv *env, jclass clazz, jstring name) {
	const char *nameUtf = (*env)->GetStringUTFChars(env, name, 0);
	void* handle = dlopen(nameUtf, RTLD_GLOBAL | RTLD_LAZY);
	if (!handle) {
		const char* error = dlerror();
		fprintf(stderr, "dlopen failed for %s: %s\n", nameUtf, error != NULL ? error : "(unknown)");
	} else {
		;
	}
	(*env)->ReleaseStringUTFChars(env, name, nameUtf);
	return handle != NULL;
}

JNIEXPORT jint JNICALL Java_net_kdt_pojavlaunch_utils_JREUtils_chdir(JNIEnv *env, jclass clazz, jstring nameStr) {
	const char *name = (*env)->GetStringUTFChars(env, nameStr, NULL);
	int retval = chdir(name);
	(*env)->ReleaseStringUTFChars(env, nameStr, name);
	return retval;
}

JNIEXPORT jboolean JNICALL Java_net_kdt_pojavlaunch_utils_JREUtils_redirectStdioToFile(
    JNIEnv *env,
    jclass clazz,
    jstring filePath,
    jboolean append
) {
    if (filePath == NULL) {
        return JNI_FALSE;
    }
    const char *path = (*env)->GetStringUTFChars(env, filePath, NULL);
    if (path == NULL) {
        return JNI_FALSE;
    }

    int flags = O_CREAT | O_WRONLY | (append == JNI_TRUE ? O_APPEND : O_TRUNC);
    int log_fd = open(path, flags, 0644);
    if (log_fd < 0) {
        (*env)->ReleaseStringUTFChars(env, filePath, path);
        return JNI_FALSE;
    }

    bool redirected = true;
    if (dup2(log_fd, STDOUT_FILENO) < 0) {
        redirected = false;
    } else if (dup2(log_fd, STDERR_FILENO) < 0) {
        redirected = false;
    }

    if (log_fd > STDERR_FILENO) {
        close(log_fd);
    }

    if (!redirected) {
        (*env)->ReleaseStringUTFChars(env, filePath, path);
        return JNI_FALSE;
    }

    // Line-buffer stdout and make stderr unbuffered for prompt crash detail flush.
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    (*env)->ReleaseStringUTFChars(env, filePath, path);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL Java_net_kdt_pojavlaunch_utils_JREUtils_executeBinary(JNIEnv *env, jclass clazz, jobjectArray cmdArgs) {
	jclass exception_cls = (*env)->FindClass(env, "java/lang/UnsatisfiedLinkError");
	jstring execFile = (*env)->GetObjectArrayElement(env, cmdArgs, 0);
	
	char *exec_file_c = (char*) (*env)->GetStringUTFChars(env, execFile, 0);
	void *exec_binary_handle = dlopen(exec_file_c, RTLD_LAZY);
	
	// (*env)->ReleaseStringUTFChars(env, ldLibraryPath, ld_library_path_c);
	(*env)->ReleaseStringUTFChars(env, execFile, exec_file_c);
	
	char *exec_error_c = dlerror();
	if (exec_error_c != NULL) {
		;
		(*env)->ThrowNew(env, exception_cls, exec_error_c);
		if (exec_binary_handle != NULL) {
			dlclose(exec_binary_handle);
		}
		return -1;
	}
	
	Main_Function_t Main_Function;
	Main_Function = (Main_Function_t) dlsym(exec_binary_handle, "main");
	
	exec_error_c = dlerror();
	if (exec_error_c != NULL) {
		;
		(*env)->ThrowNew(env, exception_cls, exec_error_c);
		if (exec_binary_handle != NULL) {
			dlclose(exec_binary_handle);
		}
		return -1;
	}
	
	int cmd_argv = (*env)->GetArrayLength(env, cmdArgs);
	char **cmd_args_c = convert_to_char_array(env, cmdArgs);
	int result;
	if (cmd_args_c == NULL) {
		fprintf(stderr, "executeBinary: failed to copy command arguments\n");
		if (exec_binary_handle != NULL) {
			dlclose(exec_binary_handle);
		}
		return -1;
	}
	result = Main_Function(cmd_argv, cmd_args_c);
	free_char_array(env, cmdArgs, (const char **) cmd_args_c);
	return result;
}

JNIEnv* get_attached_env(JavaVM* jvm) {
    JNIEnv *jvm_env = NULL;
    jint env_result = (*jvm)->GetEnv(jvm, (void**)&jvm_env, JNI_VERSION_1_4);
    if(env_result == JNI_EDETACHED) {
        env_result = (*jvm)->AttachCurrentThread(jvm, &jvm_env, NULL);
    }
    if(env_result != JNI_OK) {
        printf("get_attached_env failed: %i\n", env_result);
        return NULL;
    }
    return jvm_env;
}

// METHOD 2
/*
JNIEXPORT jint JNICALL Java_net_kdt_pojavlaunch_utils_JREUtils_executeForkedBinary(JNIEnv *env, jclass clazz, jobjectArray cmdArgs) {
	int x, status;
	x = fork();
	if (x > 0) {
		wait(&status);
	} else {
		execvpe();
	}
	return status;
}
*/

