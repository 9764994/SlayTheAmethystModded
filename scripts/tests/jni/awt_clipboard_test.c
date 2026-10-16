#include <errno.h>
static int fail_create;
#include <pthread.h>
static int test_pthread_create(pthread_t* thread, const pthread_attr_t* attr, void* (*start)(void*), void* arg) {
    return fail_create ? EAGAIN : pthread_create(thread, attr, start, arg);
}
#define pthread_create test_pthread_create
#define JNI_OnLoad unused_awt_onload
#include "../../../app/src/main/jni/awt_bridge.c"
#undef JNI_OnLoad
#undef pthread_create

static jint detached(JavaVM* vm, void** env, jint version) { (void)vm; (void)version; *env=NULL; return JNI_EDETACHED; }
static jint fail_attach(JavaVM* vm, void** env, void* args) { (void)vm; (void)args; *env=NULL; return JNI_ERR; }
static const struct JNIInvokeInterface_ fake_table = { .GetEnv=detached, .AttachCurrentThread=fail_attach };
static JavaVM fake_vm = &fake_table;

JNIEXPORT void JNICALL Java_AwtClipboardTest_setup(JNIEnv* env, jclass caller, jclass clipboard, jint mode) {
    if (class_MainActivity == NULL) {
        class_MainActivity = (*env)->NewGlobalRef(env, caller);
        method_QuerySystemClipboard = (*env)->GetStaticMethodID(env, caller, "querySystemClipboard", "()Ljava/lang/String;");
        (*env)->GetJavaVM(env, &dalvikJavaVMPtr);
    }
    if (mode == 1) runtimeJavaVMPtr = &fake_vm;
    else (*env)->GetJavaVM(env, &runtimeJavaVMPtr);
    fail_create = mode == 2;
    JNINativeMethod method = {"nQuerySystemClipboard", "()V", Java_net_java_openjdk_cacio_ctc_CTCClipboard_nQuerySystemClipboard};
    (*env)->RegisterNatives(env, clipboard, &method, 1);
}
