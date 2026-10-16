#include <assert.h>
#include <stdint.h>
#include <unistd.h>
#include "../../../app/src/main/jni/awt_bridge.c"

#define WORKERS 8
static JNIEnv fake_env;
static pthread_barrier_t first_lookup;
static atomic_int next_ref;
static atomic_int live_refs;
static atomic_bool live[1024];
static atomic_bool fail_method;
static _Thread_local int reached_barrier;
static _Thread_local int reentrant;
static int concurrent;

static jclass find_class(JNIEnv* env, const char* name) {
    (void)env; (void)name;
    if (concurrent && !reached_barrier) {
        reached_barrier=1;
        pthread_barrier_wait(&first_lookup);
    }
    return (jclass)(uintptr_t)1;
}
static jboolean no_exception(JNIEnv* env) { (void)env; return JNI_FALSE; }
static jobject new_global(JNIEnv* env, jobject value) {
    (void)env; (void)value;
    int ref=atomic_fetch_add(&next_ref,1)+1;
    assert(ref<1024);
    atomic_store(&live[ref],true);
    atomic_fetch_add(&live_refs,1);
    return (jobject)(uintptr_t)ref;
}
static void delete_local(JNIEnv* env, jobject value) { (void)env; (void)value; }
static void delete_global(JNIEnv* env, jobject value) {
    (void)env;
    assert(atomic_exchange(&live[(uintptr_t)value],false));
    atomic_fetch_sub(&live_refs,1);
}
static jmethodID lookup(JNIEnv* env, jclass type, const char* name, const char* signature) {
    (void)signature;
    assert(type != NULL && atomic_load(&live[(uintptr_t)type]));
    if (atomic_exchange(&fail_method,false)) return NULL;
    /* Hold every binding's first lookup until all workers have built their
     * own candidate; no thread can publish before the others enter lookup. */
    if (concurrent && (strcmp(name,"receiveData")==0 || strcmp(name,"getCurrentScreenRGB")==0 ||
            strcmp(name,"getCurrentKeyboardFocusManager")==0 ||
            strcmp(name,"systemClipboardDataReceived")==0 || strcmp(name,"getFrames")==0)) {
        pthread_barrier_wait(&first_lookup);
    }
    /* Simulate Java class initialization calling the bridge recursively. */
    if (reentrant && strcmp(name,"receiveData")==0) {
        reentrant=0;
        assert(ensureReceiveInput(env));
    }
    return (jmethodID)(uintptr_t)42;
}
static jfieldID field(JNIEnv* env, jclass type, const char* name, const char* signature) {
    return (jfieldID)lookup(env,type,name,signature);
}
static const struct JNINativeInterface_ table = {
    .FindClass=find_class, .NewGlobalRef=new_global, .DeleteLocalRef=delete_local,
    .DeleteGlobalRef=delete_global, .GetStaticMethodID=lookup, .GetMethodID=lookup,
    .GetFieldID=field, .ExceptionCheck=no_exception
};
static void check_ref(jclass type) { assert(type != NULL && atomic_load(&live[(uintptr_t)type])); }
static void* worker(void* ignored) {
    (void)ignored;
    assert(ensureReceiveInput(&fake_env));
    assert(ensureScreenClass(&fake_env));
    assert(ensureAwtFocusLookup(&fake_env));
    assert(ensureClipboardCallback(&fake_env,(jclass)(uintptr_t)1));
    assert(ensureMoveWindowIds(&fake_env));
    check_ref(class_CTCAndroidInput); check_ref(class_CTCScreen);
    check_ref(class_KeyboardFocusManager); check_ref(class_TextComponent); check_ref(class_JTextComponent);
    check_ref(class_CTCClipboard); check_ref(class_Frame); check_ref(class_Rectangle);
    assert(method_ReceiveInput && method_GetRGB && method_GetCurrentKeyboardFocusManager && method_GetFocusOwner);
    assert(method_SystemClipboardDataReceived && method_GetFrames && method_GetBounds && method_SetBounds);
    assert(constructor_Rectangle && field_x && field_y);
    return NULL;
}
int main(void) {
    fake_env=&table;
    atomic_store(&fail_method,true);
    assert(!ensureReceiveInput(&fake_env));
    assert(!atomic_load(&inputBindingReady) && atomic_load(&live_refs)==0);
    concurrent=1;
    pthread_barrier_init(&first_lookup,NULL,WORKERS);
    pthread_t threads[WORKERS];
    for (int i=0;i<WORKERS;i++) assert(pthread_create(&threads[i],NULL,worker,NULL)==0);
    for (int i=0;i<WORKERS;i++) pthread_join(threads[i],NULL);
    pthread_barrier_destroy(&first_lookup);
    assert(atomic_load(&live_refs)==8); // Only the published global refs survive.
    /* Reset the input cache only after all readers have exited, then test reentry. */
    concurrent=0;
    delete_global(&fake_env,class_CTCAndroidInput);
    class_CTCAndroidInput=NULL; method_ReceiveInput=NULL;
    atomic_store(&inputBindingReady,false);
    reentrant=1;
    assert(ensureReceiveInput(&fake_env));
    check_ref(class_CTCAndroidInput);
    assert(atomic_load(&live_refs)==8);
    puts("PASS AWT caches: concurrent first use, losing-candidate cleanup, failed lookup retry and class-init reentry");
}
