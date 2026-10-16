#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>

static JavaVM* dalvikJavaVMPtr;
static JavaVM* runtimeJavaVMPtr;

static jclass class_CTCScreen;
static jmethodID method_GetRGB;

static jclass class_CTCAndroidInput;
static jmethodID method_ReceiveInput;

static jclass class_MainActivity;
static jmethodID method_OpenLink;
static jmethodID method_OpenPath;
static jmethodID method_QuerySystemClipboard;
static jmethodID method_PutClipboardData;

static jclass class_Frame;
static jclass class_Rectangle;
static jclass class_CTCClipboard;
static jmethodID constructor_Rectangle;
static jmethodID method_GetFrames;
static jmethodID method_GetBounds;
static jmethodID method_SetBounds;
static jmethodID method_SystemClipboardDataReceived;

static jfieldID field_x;
static jfieldID field_y;

static jclass class_KeyboardFocusManager;
static jmethodID method_GetCurrentKeyboardFocusManager;
static jmethodID method_GetFocusOwner;
static jclass class_TextComponent;
static jclass class_JTextComponent;

/* Bindings are immutable after release publication. JNI lookups run outside
 * this lock: FindClass/GetMethodID can initialize Java classes and reenter us. */
static pthread_mutex_t bindingLock = PTHREAD_MUTEX_INITIALIZER;
static atomic_bool inputBindingReady;
static atomic_bool focusBindingReady;
static atomic_bool screenBindingReady;
static atomic_bool clipboardBindingReady;
static atomic_bool windowBindingReady;

/* Game thread holds CTCClipboard.sSyncLock for the whole nQuery call and only
 * wait()s after it returns. The callback has to run on another thread so its
 * notifyAll cannot land before that wait.
 */
typedef struct {
    char* data;
    char* mime;
    pthread_mutex_t lock;
    pthread_cond_t ready;
    int attachDone;
    int attachSucceeded;
    int acknowledged;
} ClipboardPayload;

static void clearJniException(JNIEnv* env, const char* where) {
    if (env == NULL || (*env)->ExceptionCheck(env) != JNI_TRUE) {
        return;
    }
    fprintf(stderr, "awt_bridge: exception during %s\n", where);
    (*env)->ExceptionDescribe(env);
    (*env)->ExceptionClear(env);
}

static JNIEnv* jniEnvFor(JavaVM* vm, jint version, const char* where, int* attached) {
    JNIEnv* env = NULL;
    jint status;

    if (attached != NULL) {
        *attached = 0;
    }
    if (vm == NULL) {
        static atomic_bool logged;
        if (!atomic_exchange_explicit(&logged, true, memory_order_relaxed)) {
            fprintf(stderr, "awt_bridge: %s: JavaVM is null\n", where);
        }
        return NULL;
    }
    status = (*vm)->GetEnv(vm, (void**)&env, version);
    if (status == JNI_OK) {
        return env;
    }
    if (status != JNI_EDETACHED) {
        fprintf(stderr, "awt_bridge: %s: GetEnv failed: %d\n", where, status);
        return NULL;
    }
    status = (*vm)->AttachCurrentThread(vm, &env, NULL);
    if (status != JNI_OK || env == NULL) {
        fprintf(stderr, "awt_bridge: %s: AttachCurrentThread failed: %d\n", where, status);
        return NULL;
    }
    if (attached != NULL) {
        *attached = 1;
    }
    return env;
}

/* Only a thread this call attached may be detached. Detach of a thread the VM already owned aborts it. */
static void releaseAttached(JavaVM* vm, int attached, const char* where) {
    jint status;

    if (!attached || vm == NULL) {
        return;
    }
    status = (*vm)->DetachCurrentThread(vm);
    if (status != JNI_OK) {
        fprintf(stderr, "awt_bridge: %s: DetachCurrentThread failed: %d\n", where, status);
    }
}

static jclass newGlobalClass(JNIEnv* env, const char* name, int reportFailure) {
    jclass local;
    jclass global;

    local = (*env)->FindClass(env, name);
    if ((*env)->ExceptionCheck(env) == JNI_TRUE) {
        if (reportFailure) {
            fprintf(stderr, "awt_bridge: FindClass %s failed\n", name);
            (*env)->ExceptionDescribe(env);
        }
        (*env)->ExceptionClear(env);
    }
    if (local == NULL) {
        return NULL;
    }
    global = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (global == NULL) {
        fprintf(stderr, "awt_bridge: NewGlobalRef %s failed\n", name);
        clearJniException(env, name);
    }
    return global;
}

static void dropGlobalClass(JNIEnv* env, jclass* slot) {
    if (env == NULL || slot == NULL || *slot == NULL) {
        return;
    }
    (*env)->DeleteGlobalRef(env, *slot);
    *slot = NULL;
}

static jmethodID lookupStaticMethod(JNIEnv* env, jclass clazz, const char* name, const char* signature) {
    jmethodID method = (*env)->GetStaticMethodID(env, clazz, name, signature);
    if (method == NULL) {
        fprintf(stderr, "awt_bridge: GetStaticMethodID %s%s failed\n", name, signature);
        clearJniException(env, name);
    }
    return method;
}

static jmethodID lookupMethod(JNIEnv* env, jclass clazz, const char* name, const char* signature) {
    jmethodID method = (*env)->GetMethodID(env, clazz, name, signature);
    if (method == NULL) {
        fprintf(stderr, "awt_bridge: GetMethodID %s%s failed\n", name, signature);
        clearJniException(env, name);
    }
    return method;
}

static jfieldID lookupField(JNIEnv* env, jclass clazz, const char* name, const char* signature) {
    jfieldID field = (*env)->GetFieldID(env, clazz, name, signature);
    if (field == NULL) {
        fprintf(stderr, "awt_bridge: GetFieldID %s%s failed\n", name, signature);
        clearJniException(env, name);
    }
    return field;
}

static char* dupUtf(const char* value) {
    size_t length;
    char* copy;

    if (value == NULL) {
        return NULL;
    }
    length = strlen(value) + 1;
    copy = (char*) malloc(length);
    if (copy == NULL) {
        return NULL;
    }
    memcpy(copy, value, length);
    return copy;
}

static void releaseUtfChars(JNIEnv* env, jstring value, const char* chars) {
    if (env != NULL && value != NULL && chars != NULL) {
        (*env)->ReleaseStringUTFChars(env, value, chars);
    }
}

static const char* readUtfOrAbort(JNIEnv* env, jstring value, const char** charsOut, const char* where) {
    if (value == NULL) {
        *charsOut = NULL;
        return NULL;
    }
    *charsOut = (*env)->GetStringUTFChars(env, value, NULL);
    if (*charsOut == NULL) {
        fprintf(stderr, "awt_bridge: GetStringUTFChars failed during %s\n", where);
        if ((*env)->ExceptionCheck(env) == JNI_TRUE) {
            (*env)->ExceptionDescribe(env);
        }
        return where;
    }
    return NULL;
}

static jboolean ensureReceiveInput(JNIEnv* env) {
    jclass inputClass;
    jmethodID receiveInput;

    if (atomic_load_explicit(&inputBindingReady, memory_order_acquire)) {
        return JNI_TRUE;
    }

    inputClass = newGlobalClass(env, "net/java/openjdk/cacio/ctc/CTCAndroidInput", 0);
    if (inputClass == NULL) {
        inputClass = newGlobalClass(env, "com/github/caciocavallosilano/cacio/ctc/CTCAndroidInput", 0);
    }
    if (inputClass == NULL) {
        static atomic_bool logged;
        if (!atomic_exchange_explicit(&logged, true, memory_order_relaxed)) {
            fprintf(stderr, "awt_bridge: CTCAndroidInput not found\n");
        }
        return JNI_FALSE;
    }
    receiveInput = lookupStaticMethod(env, inputClass, "receiveData", "(IIIII)V");
    if (receiveInput == NULL) {
        (*env)->DeleteGlobalRef(env, inputClass);
        return JNI_FALSE;
    }
    pthread_mutex_lock(&bindingLock);
    if (!atomic_load_explicit(&inputBindingReady, memory_order_relaxed)) {
        class_CTCAndroidInput = inputClass;
        method_ReceiveInput = receiveInput;
        atomic_store_explicit(&inputBindingReady, true, memory_order_release);
        inputClass = NULL;
    }
    pthread_mutex_unlock(&bindingLock);
    if (inputClass != NULL) (*env)->DeleteGlobalRef(env, inputClass);
    return JNI_TRUE;
}

static jboolean ensureAwtFocusLookup(JNIEnv* env) {
    if (atomic_load_explicit(&focusBindingReady, memory_order_acquire)) return JNI_TRUE;
    jclass manager = newGlobalClass(env, "java/awt/KeyboardFocusManager", 0);
    if (manager == NULL) return JNI_FALSE;
    jmethodID current = lookupStaticMethod(env, manager, "getCurrentKeyboardFocusManager",
                                          "()Ljava/awt/KeyboardFocusManager;");
    jmethodID owner = lookupMethod(env, manager, "getFocusOwner", "()Ljava/awt/Component;");
    jclass text = newGlobalClass(env, "java/awt/TextComponent", 0);
    jclass swingText = newGlobalClass(env, "javax/swing/text/JTextComponent", 0);
    jboolean success = current != NULL && owner != NULL && (text != NULL || swingText != NULL);
    if (success) {
        pthread_mutex_lock(&bindingLock);
        if (!atomic_load_explicit(&focusBindingReady, memory_order_relaxed)) {
            class_KeyboardFocusManager = manager;
            method_GetCurrentKeyboardFocusManager = current;
            method_GetFocusOwner = owner;
            class_TextComponent = text;
            class_JTextComponent = swingText;
            atomic_store_explicit(&focusBindingReady, true, memory_order_release);
            manager = text = swingText = NULL;
        }
        pthread_mutex_unlock(&bindingLock);
    }
    if (manager != NULL) (*env)->DeleteGlobalRef(env, manager);
    if (text != NULL) (*env)->DeleteGlobalRef(env, text);
    if (swingText != NULL) (*env)->DeleteGlobalRef(env, swingText);
    return success;
}

static jboolean ensureScreenClass(JNIEnv* env) {
    jclass screen;
    jmethodID getRgb;

    if (atomic_load_explicit(&screenBindingReady, memory_order_acquire)) {
        return JNI_TRUE;
    }

    screen = newGlobalClass(env, "net/java/openjdk/cacio/ctc/CTCScreen", 0);
    if (screen == NULL) {
        screen = newGlobalClass(env, "com/github/caciocavallosilano/cacio/ctc/CTCScreen", 0);
    }
    if (screen == NULL) {
        static atomic_bool logged;
        if (!atomic_exchange_explicit(&logged, true, memory_order_relaxed)) {
            fprintf(stderr, "awt_bridge: CTCScreen not found\n");
        }
        return JNI_FALSE;
    }
    getRgb = lookupStaticMethod(env, screen, "getCurrentScreenRGB", "()[I");
    if (getRgb == NULL) {
        (*env)->DeleteGlobalRef(env, screen);
        return JNI_FALSE;
    }
    pthread_mutex_lock(&bindingLock);
    if (!atomic_load_explicit(&screenBindingReady, memory_order_relaxed)) {
        class_CTCScreen = screen;
        method_GetRGB = getRgb;
        atomic_store_explicit(&screenBindingReady, true, memory_order_release);
        screen = NULL;
    }
    pthread_mutex_unlock(&bindingLock);
    if (screen != NULL) (*env)->DeleteGlobalRef(env, screen);
    return JNI_TRUE;
}

static jboolean ensureClipboardCallback(JNIEnv* env, jclass clazz) {
    jclass global;
    jmethodID method;

    if (atomic_load_explicit(&clipboardBindingReady, memory_order_acquire)) {
        return JNI_TRUE;
    }

    global = (*env)->NewGlobalRef(env, clazz);
    if (global == NULL) {
        fprintf(stderr, "awt_bridge: NewGlobalRef CTCClipboard failed\n");
        clearJniException(env, "CTCClipboard");
        return JNI_FALSE;
    }
    method = lookupStaticMethod(
            env,
            global,
            "systemClipboardDataReceived",
            "(Ljava/lang/String;Ljava/lang/String;)V"
    );
    if (method == NULL) {
        (*env)->DeleteGlobalRef(env, global);
        return JNI_FALSE;
    }
    pthread_mutex_lock(&bindingLock);
    if (!atomic_load_explicit(&clipboardBindingReady, memory_order_relaxed)) {
        class_CTCClipboard = global;
        method_SystemClipboardDataReceived = method;
        atomic_store_explicit(&clipboardBindingReady, true, memory_order_release);
        global = NULL;
    }
    pthread_mutex_unlock(&bindingLock);
    if (global != NULL) (*env)->DeleteGlobalRef(env, global);
    return JNI_TRUE;
}

static jboolean ensureMoveWindowIds(JNIEnv* env) {
    jclass frame;
    jclass rectangle;

    if (atomic_load_explicit(&windowBindingReady, memory_order_acquire)) {
        return JNI_TRUE;
    }

    frame = newGlobalClass(env, "java/awt/Frame", 1);
    rectangle = newGlobalClass(env, "java/awt/Rectangle", 1);
    if (frame == NULL || rectangle == NULL) {
        fprintf(stderr, "awt_bridge: Frame or Rectangle not found\n");
        if (frame != NULL) {
            (*env)->DeleteGlobalRef(env, frame);
        }
        if (rectangle != NULL) {
            (*env)->DeleteGlobalRef(env, rectangle);
        }
        return JNI_FALSE;
    }

    jmethodID frames = lookupStaticMethod(env, frame, "getFrames", "()[Ljava/awt/Frame;");
    jmethodID bounds = lookupMethod(env, frame, "getBounds", "(Ljava/awt/Rectangle;)Ljava/awt/Rectangle;");
    jmethodID setBounds = lookupMethod(env, frame, "setBounds", "(Ljava/awt/Rectangle;)V");
    jmethodID constructor = lookupMethod(env, rectangle, "<init>", "()V");
    jfieldID x = lookupField(env, rectangle, "x", "I");
    jfieldID y = lookupField(env, rectangle, "y", "I");
    if (frames == NULL || bounds == NULL || setBounds == NULL ||
        constructor == NULL || x == NULL || y == NULL) {
        (*env)->DeleteGlobalRef(env, frame);
        (*env)->DeleteGlobalRef(env, rectangle);
        return JNI_FALSE;
    }

    pthread_mutex_lock(&bindingLock);
    if (!atomic_load_explicit(&windowBindingReady, memory_order_relaxed)) {
        class_Frame = frame;
        class_Rectangle = rectangle;
        method_GetFrames = frames;
        method_GetBounds = bounds;
        method_SetBounds = setBounds;
        constructor_Rectangle = constructor;
        field_x = x;
        field_y = y;
        atomic_store_explicit(&windowBindingReady, true, memory_order_release);
        frame = rectangle = NULL;
    }
    pthread_mutex_unlock(&bindingLock);
    if (frame != NULL) (*env)->DeleteGlobalRef(env, frame);
    if (rectangle != NULL) (*env)->DeleteGlobalRef(env, rectangle);
    return JNI_TRUE;
}

static void* clipboardCallbackThread(void* arg) {
    ClipboardPayload* payload = (ClipboardPayload*) arg;
    JavaVM* vm = runtimeJavaVMPtr;
    JNIEnv* gameEnv = NULL;
    int detachAfter = 0;
    jint status = JNI_ERR;

    if (vm == NULL || !atomic_load_explicit(&clipboardBindingReady, memory_order_acquire)) {
        fprintf(stderr, "awt_bridge: clipboard callback unavailable\n");
    } else {
        status = (*vm)->GetEnv(vm, (void**)&gameEnv, JNI_VERSION_1_4);
        if (status == JNI_EDETACHED) {
            status = (*vm)->AttachCurrentThread(vm, &gameEnv, NULL);
            if (status == JNI_OK) {
                detachAfter = 1;
            }
        }
    }
    int attachedSuccessfully = status == JNI_OK && gameEnv != NULL;
    if (!attachedSuccessfully) {
        fprintf(stderr, "awt_bridge: clipboard callback attach failed: %d\n", status);
    }
    /* Confirm attachment, not callback completion. The query still holds the
     * Java clipboard monitor, so waiting for the callback here would deadlock. */
    pthread_mutex_lock(&payload->lock);
    payload->attachSucceeded = attachedSuccessfully;
    payload->attachDone = 1;
    pthread_cond_signal(&payload->ready);
    while (!payload->acknowledged) pthread_cond_wait(&payload->ready, &payload->lock);
    pthread_mutex_unlock(&payload->lock);
    if (attachedSuccessfully) {
        jstring data = NULL;
        jstring mime = NULL;
        if (payload->data != NULL) {
            data = (*gameEnv)->NewStringUTF(gameEnv, payload->data);
        }
        if (payload->mime != NULL && !(*gameEnv)->ExceptionCheck(gameEnv)) {
            mime = (*gameEnv)->NewStringUTF(gameEnv, payload->mime);
        }
        if ((payload->data != NULL && data == NULL) || (payload->mime != NULL && mime == NULL)) {
            fprintf(stderr, "awt_bridge: clipboard callback string creation failed\n");
            clearJniException(gameEnv, "clipboard NewStringUTF");
            if (data != NULL) {
                (*gameEnv)->DeleteLocalRef(gameEnv, data);
            }
            if (mime != NULL) {
                (*gameEnv)->DeleteLocalRef(gameEnv, mime);
            }
            data = NULL;
            mime = NULL;
        }
        (*gameEnv)->CallStaticVoidMethod(
                gameEnv,
                class_CTCClipboard,
                method_SystemClipboardDataReceived,
                data,
                mime
        );
        clearJniException(gameEnv, "systemClipboardDataReceived");
        if (data != NULL) {
            (*gameEnv)->DeleteLocalRef(gameEnv, data);
        }
        if (mime != NULL) {
            (*gameEnv)->DeleteLocalRef(gameEnv, mime);
        }
        if (detachAfter) {
            status = (*vm)->DetachCurrentThread(vm);
            if (status != JNI_OK) {
                fprintf(stderr, "awt_bridge: clipboard callback detach failed: %d\n", status);
            }
        }
    }

    free(payload->data);
    free(payload->mime);
    pthread_cond_destroy(&payload->ready);
    pthread_mutex_destroy(&payload->lock);
    free(payload);
    return NULL;
}

static int startClipboardCallback(char* dataOwned, const char* mime) {
    ClipboardPayload* payload;
    pthread_t thread;
    int rc;

    payload = (ClipboardPayload*) calloc(1, sizeof(ClipboardPayload));
    if (payload == NULL) {
        fprintf(stderr, "awt_bridge: clipboard payload allocation failed\n");
        free(dataOwned);
        return 0;
    }
    payload->data = dataOwned;
    rc = pthread_mutex_init(&payload->lock, NULL);
    if (rc != 0) {
        free(payload->data);
        free(payload);
        return 0;
    }
    rc = pthread_cond_init(&payload->ready, NULL);
    if (rc != 0) {
        pthread_mutex_destroy(&payload->lock);
        free(payload->data);
        free(payload);
        return 0;
    }
    if (mime != NULL) {
        payload->mime = dupUtf(mime);
        if (payload->mime == NULL) {
            fprintf(stderr, "awt_bridge: clipboard mime allocation failed\n");
            free(payload->data);
            payload->data = NULL;
        }
    }

    rc = pthread_create(&thread, NULL, clipboardCallbackThread, payload);
    if (rc != 0) {
        fprintf(stderr, "awt_bridge: pthread_create clipboard callback failed: %s\n", strerror(rc));
        free(payload->data);
        free(payload->mime);
        pthread_cond_destroy(&payload->ready);
        pthread_mutex_destroy(&payload->lock);
        free(payload);
        return 0;
    }
    pthread_mutex_lock(&payload->lock);
    while (!payload->attachDone) pthread_cond_wait(&payload->ready, &payload->lock);
    int success = payload->attachSucceeded;
    if (success) pthread_detach(thread);
    payload->acknowledged = 1;
    pthread_cond_signal(&payload->ready);
    pthread_mutex_unlock(&payload->lock);
    /* On failure the worker cannot call Java; join it before throwing on the
     * query thread, so Cacio never enters its unconditional wait. */
    if (!success) pthread_join(thread, NULL);
    return success;
}

static char* readAndroidClipboard() {
    JNIEnv* dalvikEnv;
    jstring value;
    const char* chars;
    char* copy;
    int attached = 0;

    dalvikEnv = jniEnvFor(dalvikJavaVMPtr, JNI_VERSION_1_6, "querySystemClipboard", &attached);
    if (dalvikEnv == NULL || class_MainActivity == NULL || method_QuerySystemClipboard == NULL) {
        fprintf(stderr, "awt_bridge: querySystemClipboard is unavailable\n");
        releaseAttached(dalvikJavaVMPtr, attached, "querySystemClipboard");
        return NULL;
    }

    value = (jstring) (*dalvikEnv)->CallStaticObjectMethod(
            dalvikEnv,
            class_MainActivity,
            method_QuerySystemClipboard
    );
    if ((*dalvikEnv)->ExceptionCheck(dalvikEnv) == JNI_TRUE) {
        clearJniException(dalvikEnv, "querySystemClipboard");
        if (value != NULL) {
            (*dalvikEnv)->DeleteLocalRef(dalvikEnv, value);
        }
        releaseAttached(dalvikJavaVMPtr, attached, "querySystemClipboard");
        return NULL;
    }
    if (value == NULL) {
        releaseAttached(dalvikJavaVMPtr, attached, "querySystemClipboard");
        return NULL;
    }

    chars = (*dalvikEnv)->GetStringUTFChars(dalvikEnv, value, NULL);
    if (chars == NULL) {
        fprintf(stderr, "awt_bridge: GetStringUTFChars failed during querySystemClipboard\n");
        clearJniException(dalvikEnv, "querySystemClipboard");
        (*dalvikEnv)->DeleteLocalRef(dalvikEnv, value);
        releaseAttached(dalvikJavaVMPtr, attached, "querySystemClipboard");
        return NULL;
    }
    copy = chars[0] == '\0' ? NULL : dupUtf(chars);
    if (chars[0] != '\0' && copy == NULL) {
        fprintf(stderr, "awt_bridge: clipboard text allocation failed\n");
    }
    (*dalvikEnv)->ReleaseStringUTFChars(dalvikEnv, value, chars);
    (*dalvikEnv)->DeleteLocalRef(dalvikEnv, value);
    releaseAttached(dalvikJavaVMPtr, attached, "querySystemClipboard");
    return copy;
}

static void callDalvikOneString(jmethodID method, JNIEnv* env, jstring value, const char* where) {
    const char* chars = NULL;
    JNIEnv* dalvikEnv;
    jstring arg = NULL;
    int attached = 0;

    if (readUtfOrAbort(env, value, &chars, where) != NULL) {
        return;
    }
    if (class_MainActivity == NULL || method == NULL) {
        fprintf(stderr, "awt_bridge: %s is unavailable\n", where);
        releaseUtfChars(env, value, chars);
        return;
    }
    dalvikEnv = jniEnvFor(dalvikJavaVMPtr, JNI_VERSION_1_6, where, &attached);
    if (dalvikEnv == NULL) {
        releaseUtfChars(env, value, chars);
        return;
    }
    if (chars != NULL) {
        arg = (*dalvikEnv)->NewStringUTF(dalvikEnv, chars);
        if (arg == NULL) {
            fprintf(stderr, "awt_bridge: NewStringUTF failed during %s\n", where);
            clearJniException(dalvikEnv, where);
            releaseUtfChars(env, value, chars);
            releaseAttached(dalvikJavaVMPtr, attached, where);
            return;
        }
    }
    (*dalvikEnv)->CallStaticVoidMethod(dalvikEnv, class_MainActivity, method, arg);
    clearJniException(dalvikEnv, where);
    if (arg != NULL) {
        (*dalvikEnv)->DeleteLocalRef(dalvikEnv, arg);
    }
    releaseUtfChars(env, value, chars);
    releaseAttached(dalvikJavaVMPtr, attached, where);
}

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env = NULL;
    jclass localMainActivity;
    (void) reserved;

    if (dalvikJavaVMPtr == NULL) {
        dalvikJavaVMPtr = vm;
        if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_4) != JNI_OK || env == NULL) {
            fprintf(stderr, "awt_bridge: JNI_OnLoad GetEnv failed\n");
            return JNI_VERSION_1_4;
        }
        localMainActivity = (*env)->FindClass(env, "net/kdt/pojavlaunch/MainActivity");
        if (localMainActivity == NULL || (*env)->ExceptionCheck(env) == JNI_TRUE) {
            fprintf(stderr, "awt_bridge: MainActivity not found\n");
            clearJniException(env, "MainActivity");
            if (localMainActivity != NULL) {
                (*env)->DeleteLocalRef(env, localMainActivity);
            }
            return JNI_VERSION_1_4;
        }
        class_MainActivity = (*env)->NewGlobalRef(env, localMainActivity);
        (*env)->DeleteLocalRef(env, localMainActivity);
        if (class_MainActivity == NULL) {
            fprintf(stderr, "awt_bridge: NewGlobalRef MainActivity failed\n");
            clearJniException(env, "MainActivity");
            return JNI_VERSION_1_4;
        }

        method_OpenLink = lookupStaticMethod(env, class_MainActivity, "openLink", "(Ljava/lang/String;)V");
        method_OpenPath = lookupStaticMethod(env, class_MainActivity, "openLink", "(Ljava/lang/String;)V");
        method_QuerySystemClipboard = lookupStaticMethod(env, class_MainActivity, "querySystemClipboard", "()Ljava/lang/String;");
        method_PutClipboardData = lookupStaticMethod(
                env,
                class_MainActivity,
                "putClipboardData",
                "(Ljava/lang/String;Ljava/lang/String;)V"
        );
        if (method_OpenLink == NULL && method_OpenPath == NULL &&
            method_QuerySystemClipboard == NULL && method_PutClipboardData == NULL) {
            dropGlobalClass(env, &class_MainActivity);
        }
    } else if (dalvikJavaVMPtr != vm) {
        runtimeJavaVMPtr = vm;
    }

    return JNI_VERSION_1_4;
}

JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_AWTInputBridge_nativeSendData(JNIEnv* env, jclass clazz, jint type, jint i1, jint i2, jint i3, jint i4) {
    JNIEnv* inputEnv;
    (void) env;
    (void) clazz;

    inputEnv = jniEnvFor(runtimeJavaVMPtr, JNI_VERSION_1_4, "nativeSendData", NULL);
    if (inputEnv == NULL || !ensureReceiveInput(inputEnv)) {
        return;
    }
    (*inputEnv)->CallStaticVoidMethod(
            inputEnv,
            class_CTCAndroidInput,
            method_ReceiveInput,
            type, i1, i2, i3, i4
    );
    clearJniException(inputEnv, "CTCAndroidInput.receiveData");
}

JNIEXPORT jboolean JNICALL Java_net_kdt_pojavlaunch_AWTInputBridge_nativeIsTextInputFocused(JNIEnv* env, jclass clazz) {
    JNIEnv* inputEnv;
    jobject focusManager;
    jobject focusOwner;
    jboolean focused;
    (void) env;
    (void) clazz;

    inputEnv = jniEnvFor(runtimeJavaVMPtr, JNI_VERSION_1_4, "nativeIsTextInputFocused", NULL);
    if (inputEnv == NULL || !ensureAwtFocusLookup(inputEnv)) {
        return JNI_FALSE;
    }

    focusManager = (*inputEnv)->CallStaticObjectMethod(
            inputEnv,
            class_KeyboardFocusManager,
            method_GetCurrentKeyboardFocusManager
    );
    if ((*inputEnv)->ExceptionCheck(inputEnv) == JNI_TRUE) {
        clearJniException(inputEnv, "getCurrentKeyboardFocusManager");
        if (focusManager != NULL) {
            (*inputEnv)->DeleteLocalRef(inputEnv, focusManager);
        }
        return JNI_FALSE;
    }
    if (focusManager == NULL) {
        return JNI_FALSE;
    }

    focusOwner = (*inputEnv)->CallObjectMethod(inputEnv, focusManager, method_GetFocusOwner);
    (*inputEnv)->DeleteLocalRef(inputEnv, focusManager);
    if ((*inputEnv)->ExceptionCheck(inputEnv) == JNI_TRUE) {
        clearJniException(inputEnv, "getFocusOwner");
        if (focusOwner != NULL) {
            (*inputEnv)->DeleteLocalRef(inputEnv, focusOwner);
        }
        return JNI_FALSE;
    }
    if (focusOwner == NULL) {
        return JNI_FALSE;
    }

    focused = JNI_FALSE;
    if (class_TextComponent != NULL &&
        (*inputEnv)->IsInstanceOf(inputEnv, focusOwner, class_TextComponent) == JNI_TRUE) {
        focused = JNI_TRUE;
    } else if (class_JTextComponent != NULL &&
               (*inputEnv)->IsInstanceOf(inputEnv, focusOwner, class_JTextComponent) == JNI_TRUE) {
        focused = JNI_TRUE;
    }
    (*inputEnv)->DeleteLocalRef(inputEnv, focusOwner);
    return focused;
}

JNIEXPORT jintArray JNICALL Java_net_kdt_pojavlaunch_utils_JREUtils_renderAWTScreenFrame(JNIEnv* env, jclass clazz) {
    JNIEnv* graphicsEnv;
    jintArray jreRgbArray;
    jintArray androidRgbArray;
    jint* rgbArray;
    jsize arrayLength;
    (void) clazz;

    graphicsEnv = jniEnvFor(runtimeJavaVMPtr, JNI_VERSION_1_4, "renderAWTScreenFrame", NULL);
    if (graphicsEnv == NULL || !ensureScreenClass(graphicsEnv)) {
        return NULL;
    }

    jreRgbArray = (jintArray) (*graphicsEnv)->CallStaticObjectMethod(
            graphicsEnv,
            class_CTCScreen,
            method_GetRGB
    );
    if ((*graphicsEnv)->ExceptionCheck(graphicsEnv) == JNI_TRUE) {
        clearJniException(graphicsEnv, "getCurrentScreenRGB");
        if (jreRgbArray != NULL) {
            (*graphicsEnv)->DeleteLocalRef(graphicsEnv, jreRgbArray);
        }
        return NULL;
    }
    if (jreRgbArray == NULL) {
        return NULL;
    }

    arrayLength = (*graphicsEnv)->GetArrayLength(graphicsEnv, jreRgbArray);
    if ((*graphicsEnv)->ExceptionCheck(graphicsEnv) == JNI_TRUE) {
        clearJniException(graphicsEnv, "GetArrayLength");
        (*graphicsEnv)->DeleteLocalRef(graphicsEnv, jreRgbArray);
        return NULL;
    }
    rgbArray = (*graphicsEnv)->GetIntArrayElements(graphicsEnv, jreRgbArray, NULL);
    if (rgbArray == NULL && arrayLength > 0) {
        fprintf(stderr, "awt_bridge: GetIntArrayElements failed\n");
        clearJniException(graphicsEnv, "GetIntArrayElements");
        (*graphicsEnv)->DeleteLocalRef(graphicsEnv, jreRgbArray);
        return NULL;
    }

    androidRgbArray = (*env)->NewIntArray(env, arrayLength);
    if (androidRgbArray == NULL) {
        fprintf(stderr, "awt_bridge: NewIntArray failed\n");
        clearJniException(env, "NewIntArray");
        if (rgbArray != NULL) {
            (*graphicsEnv)->ReleaseIntArrayElements(graphicsEnv, jreRgbArray, rgbArray, JNI_ABORT);
        }
        (*graphicsEnv)->DeleteLocalRef(graphicsEnv, jreRgbArray);
        return NULL;
    }

    if (arrayLength > 0) {
        (*env)->SetIntArrayRegion(env, androidRgbArray, 0, arrayLength, rgbArray);
        if ((*env)->ExceptionCheck(env) == JNI_TRUE) {
            clearJniException(env, "SetIntArrayRegion");
            if (rgbArray != NULL) {
                (*graphicsEnv)->ReleaseIntArrayElements(graphicsEnv, jreRgbArray, rgbArray, JNI_ABORT);
            }
            (*graphicsEnv)->DeleteLocalRef(graphicsEnv, jreRgbArray);
            (*env)->DeleteLocalRef(env, androidRgbArray);
            return NULL;
        }
    }

    if (rgbArray != NULL) {
        (*graphicsEnv)->ReleaseIntArrayElements(graphicsEnv, jreRgbArray, rgbArray, JNI_ABORT);
    }
    (*graphicsEnv)->DeleteLocalRef(graphicsEnv, jreRgbArray);
    return androidRgbArray;
}

JNIEXPORT void JNICALL Java_net_java_openjdk_cacio_ctc_CTCClipboard_nQuerySystemClipboard(JNIEnv *env, jclass clazz) {
    char* text;

    if (ensureClipboardCallback(env, clazz)) {
        text = readAndroidClipboard();
        if (startClipboardCallback(text, text == NULL ? NULL : "plain")) {
            return;
        }
    }
    /* A native exception skips Cacio's unconditional wait when no callback can be scheduled. */
    jclass failure = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (failure != NULL) {
        (*env)->ThrowNew(env, failure, "Unable to schedule the system clipboard callback");
        (*env)->DeleteLocalRef(env, failure);
    }
}

JNIEXPORT void JNICALL Java_net_java_openjdk_cacio_ctc_CTCClipboard_nPutClipboardData(JNIEnv* env, jclass clazz, jstring clipboardData, jstring clipboardDataMime) {
    const char* dataChars = NULL;
    const char* mimeChars = NULL;
    JNIEnv* dalvikEnv;
    jstring data = NULL;
    jstring mime = NULL;
    (void) clazz;

    if (readUtfOrAbort(env, clipboardData, &dataChars, "nPutClipboardData") != NULL) {
        return;
    }
    if (readUtfOrAbort(env, clipboardDataMime, &mimeChars, "nPutClipboardData") != NULL) {
        releaseUtfChars(env, clipboardData, dataChars);
        return;
    }
    if (class_MainActivity == NULL || method_PutClipboardData == NULL) {
        fprintf(stderr, "awt_bridge: putClipboardData is unavailable\n");
        releaseUtfChars(env, clipboardData, dataChars);
        releaseUtfChars(env, clipboardDataMime, mimeChars);
        return;
    }

    int attached = 0;
    dalvikEnv = jniEnvFor(dalvikJavaVMPtr, JNI_VERSION_1_6, "nPutClipboardData", &attached);
    if (dalvikEnv == NULL) {
        releaseUtfChars(env, clipboardData, dataChars);
        releaseUtfChars(env, clipboardDataMime, mimeChars);
        return;
    }
    if (dataChars != NULL) {
        data = (*dalvikEnv)->NewStringUTF(dalvikEnv, dataChars);
        if (data == NULL) {
            fprintf(stderr, "awt_bridge: NewStringUTF failed during nPutClipboardData\n");
            clearJniException(dalvikEnv, "nPutClipboardData");
            releaseUtfChars(env, clipboardData, dataChars);
            releaseUtfChars(env, clipboardDataMime, mimeChars);
            releaseAttached(dalvikJavaVMPtr, attached, "nPutClipboardData");
            return;
        }
    }
    if (mimeChars != NULL) {
        mime = (*dalvikEnv)->NewStringUTF(dalvikEnv, mimeChars);
        if (mime == NULL) {
            fprintf(stderr, "awt_bridge: NewStringUTF failed during nPutClipboardData mime\n");
            clearJniException(dalvikEnv, "nPutClipboardData");
            if (data != NULL) {
                (*dalvikEnv)->DeleteLocalRef(dalvikEnv, data);
            }
            releaseUtfChars(env, clipboardData, dataChars);
            releaseUtfChars(env, clipboardDataMime, mimeChars);
            releaseAttached(dalvikJavaVMPtr, attached, "nPutClipboardData");
            return;
        }
    }

    (*dalvikEnv)->CallStaticVoidMethod(dalvikEnv, class_MainActivity, method_PutClipboardData, data, mime);
    clearJniException(dalvikEnv, "putClipboardData");
    if (data != NULL) {
        (*dalvikEnv)->DeleteLocalRef(dalvikEnv, data);
    }
    if (mime != NULL) {
        (*dalvikEnv)->DeleteLocalRef(dalvikEnv, mime);
    }
    releaseUtfChars(env, clipboardData, dataChars);
    releaseUtfChars(env, clipboardDataMime, mimeChars);
    releaseAttached(dalvikJavaVMPtr, attached, "nPutClipboardData");
}

JNIEXPORT void JNICALL Java_com_github_caciocavallosilano_cacio_ctc_CTCClipboard_nQuerySystemClipboard(JNIEnv *env, jclass clazz) {
    Java_net_java_openjdk_cacio_ctc_CTCClipboard_nQuerySystemClipboard(env, clazz);
}

JNIEXPORT void JNICALL Java_com_github_caciocavallosilano_cacio_ctc_CTCClipboard_nPutClipboardData(JNIEnv* env, jclass clazz, jstring clipboardData, jstring clipboardDataMime) {
    Java_net_java_openjdk_cacio_ctc_CTCClipboard_nPutClipboardData(env, clazz, clipboardData, clipboardDataMime);
}

JNIEXPORT void JNICALL Java_net_java_openjdk_cacio_ctc_CTCDesktopPeer_openFile(JNIEnv *env, jclass clazz, jstring filePath) {
    (void) clazz;
    callDalvikOneString(method_OpenPath, env, filePath, "openFile");
}

JNIEXPORT void JNICALL Java_net_java_openjdk_cacio_ctc_CTCDesktopPeer_openUri(JNIEnv *env, jclass clazz, jstring uri) {
    (void) clazz;
    callDalvikOneString(method_OpenLink, env, uri, "openUri");
}

JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_AWTInputBridge_nativeClipboardReceived(JNIEnv *env, jclass clazz, jstring clipboardData, jstring clipboardDataMime) {
    JNIEnv* gameEnv;
    const char* dataChars = NULL;
    const char* mimeChars = NULL;
    jstring data = NULL;
    jstring mime = NULL;
    (void) clazz;

    if (!atomic_load_explicit(&clipboardBindingReady, memory_order_acquire)) {
        return;
    }
    if (readUtfOrAbort(env, clipboardData, &dataChars, "nativeClipboardReceived") != NULL) {
        return;
    }
    if (readUtfOrAbort(env, clipboardDataMime, &mimeChars, "nativeClipboardReceived") != NULL) {
        releaseUtfChars(env, clipboardData, dataChars);
        return;
    }

    gameEnv = jniEnvFor(runtimeJavaVMPtr, JNI_VERSION_1_4, "nativeClipboardReceived", NULL);
    if (gameEnv == NULL) {
        releaseUtfChars(env, clipboardData, dataChars);
        releaseUtfChars(env, clipboardDataMime, mimeChars);
        return;
    }
    if (dataChars != NULL) {
        data = (*gameEnv)->NewStringUTF(gameEnv, dataChars);
        if (data == NULL) {
            fprintf(stderr, "awt_bridge: NewStringUTF failed during nativeClipboardReceived\n");
            clearJniException(gameEnv, "nativeClipboardReceived");
            releaseUtfChars(env, clipboardData, dataChars);
            releaseUtfChars(env, clipboardDataMime, mimeChars);
            return;
        }
    }
    if (mimeChars != NULL) {
        mime = (*gameEnv)->NewStringUTF(gameEnv, mimeChars);
        if (mime == NULL) {
            fprintf(stderr, "awt_bridge: NewStringUTF failed during nativeClipboardReceived mime\n");
            clearJniException(gameEnv, "nativeClipboardReceived");
            if (data != NULL) {
                (*gameEnv)->DeleteLocalRef(gameEnv, data);
            }
            releaseUtfChars(env, clipboardData, dataChars);
            releaseUtfChars(env, clipboardDataMime, mimeChars);
            return;
        }
    }

    (*gameEnv)->CallStaticVoidMethod(
            gameEnv,
            class_CTCClipboard,
            method_SystemClipboardDataReceived,
            data,
            mime
    );
    clearJniException(gameEnv, "nativeClipboardReceived");
    if (data != NULL) {
        (*gameEnv)->DeleteLocalRef(gameEnv, data);
    }
    if (mime != NULL) {
        (*gameEnv)->DeleteLocalRef(gameEnv, mime);
    }
    releaseUtfChars(env, clipboardData, dataChars);
    releaseUtfChars(env, clipboardDataMime, mimeChars);
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_AWTInputBridge_nativeMoveWindow(JNIEnv *env, jclass clazz, jint xoff, jint yoff) {
    JNIEnv* inputEnv;
    jobject rectangle;
    jobjectArray frames;
    jsize count;
    jsize i;
    (void) env;
    (void) clazz;

    inputEnv = jniEnvFor(runtimeJavaVMPtr, JNI_VERSION_1_4, "nativeMoveWindow", NULL);
    if (inputEnv == NULL || !ensureMoveWindowIds(inputEnv)) {
        return;
    }

    rectangle = (*inputEnv)->NewObject(inputEnv, class_Rectangle, constructor_Rectangle);
    if (rectangle == NULL || (*inputEnv)->ExceptionCheck(inputEnv) == JNI_TRUE) {
        clearJniException(inputEnv, "new Rectangle");
        if (rectangle != NULL) {
            (*inputEnv)->DeleteLocalRef(inputEnv, rectangle);
        }
        return;
    }

    frames = (jobjectArray) (*inputEnv)->CallStaticObjectMethod(inputEnv, class_Frame, method_GetFrames);
    if ((*inputEnv)->ExceptionCheck(inputEnv) == JNI_TRUE || frames == NULL) {
        clearJniException(inputEnv, "Frame.getFrames");
        if (frames != NULL) {
            (*inputEnv)->DeleteLocalRef(inputEnv, frames);
        }
        (*inputEnv)->DeleteLocalRef(inputEnv, rectangle);
        return;
    }

    count = (*inputEnv)->GetArrayLength(inputEnv, frames);
    if ((*inputEnv)->ExceptionCheck(inputEnv)) {
        clearJniException(inputEnv, "Frame array length");
        (*inputEnv)->DeleteLocalRef(inputEnv, rectangle);
        (*inputEnv)->DeleteLocalRef(inputEnv, frames);
        return;
    }
    for (i = 0; i < count; i++) {
        jobject frame = (*inputEnv)->GetObjectArrayElement(inputEnv, frames, i);
        jobject bounds;
        if ((*inputEnv)->ExceptionCheck(inputEnv) == JNI_TRUE) {
            clearJniException(inputEnv, "GetObjectArrayElement Frame");
            if (frame != NULL) {
                (*inputEnv)->DeleteLocalRef(inputEnv, frame);
            }
            continue;
        }
        if (frame == NULL) {
            continue;
        }

        bounds = (*inputEnv)->CallObjectMethod(inputEnv, frame, method_GetBounds, rectangle);
        if ((*inputEnv)->ExceptionCheck(inputEnv) == JNI_TRUE) {
            clearJniException(inputEnv, "Frame.getBounds");
            if (bounds != NULL) {
                (*inputEnv)->DeleteLocalRef(inputEnv, bounds);
            }
            (*inputEnv)->DeleteLocalRef(inputEnv, frame);
            continue;
        }
        if (bounds != NULL && bounds != rectangle) {
            (*inputEnv)->DeleteLocalRef(inputEnv, bounds);
        }

        (*inputEnv)->SetIntField(
                inputEnv,
                rectangle,
                field_x,
                (*inputEnv)->GetIntField(inputEnv, rectangle, field_x) + xoff
        );
        (*inputEnv)->SetIntField(
                inputEnv,
                rectangle,
                field_y,
                (*inputEnv)->GetIntField(inputEnv, rectangle, field_y) + yoff
        );
        (*inputEnv)->CallVoidMethod(inputEnv, frame, method_SetBounds, rectangle);
        if ((*inputEnv)->ExceptionCheck(inputEnv) == JNI_TRUE) {
            clearJniException(inputEnv, "Frame.setBounds");
        }
        (*inputEnv)->DeleteLocalRef(inputEnv, frame);
    }

    (*inputEnv)->DeleteLocalRef(inputEnv, rectangle);
    (*inputEnv)->DeleteLocalRef(inputEnv, frames);
}
