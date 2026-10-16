#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#define JNI_OnLoad unused_input_onload
#include "../../../app/src/main/jni/input_bridge_v3.c"
#undef JNI_OnLoad

static struct pojav_environ_s fixture;
struct pojav_environ_s* pojav_environ = &fixture;

JNIEXPORT jstring JNICALL Java_ClipboardTest_decode(JNIEnv* env, jclass clazz, jint action, jbyteArray value) {
    (*env)->GetJavaVM(env, &fixture.dalvikJavaVMPtr);
    if (!fixture.bridgeClazz) {
        fixture.bridgeClazz = inputGlobalClass(env, "org/lwjgl/glfw/CallbackBridge");
        fixture.method_accessAndroidClipboard = inputStaticMethod(env, fixture.bridgeClazz,
                "accessAndroidClipboard", "(ILjava/lang/String;)Ljava/lang/String;");
    }
    return Java_org_lwjgl_glfw_CallbackBridge_nativeClipboard(env, clazz, action, value);
}
