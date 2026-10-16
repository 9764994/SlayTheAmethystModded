#include "../../../app/src/main/jni/jvm_hooks/java_exec_hooks.c"

JNIEXPORT jbyteArray JNICALL Java_ExecEnvTest_rebuild(JNIEnv* env, jclass clazz, jbyteArray block) {
    (void)clazz;
    jbyteArray result;
    jint count;
    if (rebuild_env_array(env, block, "/plugin/ffmpeg", &result, &count) != 0) return NULL;
    return result;
}

JNIEXPORT void JNICALL Java_ExecEnvTest_setNativeEnv(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    setenv("JNI_LATE_ENV", "set-after-System.getenv", 1);
}
