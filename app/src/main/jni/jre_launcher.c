/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <signal.h>
#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <ucontext.h>
#include <unistd.h>
// Boardwalk: missing include
#include <string.h>
#include "utils.h"
#include "environ/environ.h"

// Uncomment to try redirect signal handling to JVM
// #define TRY_SIG2JVM

// PojavLancher: fixme: are these wrong?
#define FULL_VERSION "1.8.0-internal"
#define DOT_VERSION "1.8"

static const char* const_progname = "java";
static const char* const_launcher = "openjdk";
static const char** const_jargs = NULL;
static const char** const_appclasspath = NULL;
static const jboolean const_javaw = JNI_FALSE;
static const jboolean const_cpwildcard = JNI_TRUE;
static const jint const_ergo_class = 0; // DEFAULT_POLICY
static const char* const SIGNAL_DUMP_FILE_NAME = "last_signal_dump.txt";

typedef jint JLI_Launch_func(int argc, char ** argv, /* main argc, argc */
        int jargc, const char** jargv,          /* java args */
        int appclassc, const char** appclassv,  /* app classpath */
        const char* fullversion,                /* full version defined */
        const char* dotversion,                 /* dot version defined */
        const char* pname,                      /* program name */
        const char* lname,                      /* launcher name */
        jboolean javaargs,                      /* JAVA_ARGS */
        jboolean cpwildcard,                    /* classpath wildcard*/
        jboolean javaw,                         /* windows-only javaw */
        jint ergo                               /* ergonomics class policy */
);

typedef struct {
    uintptr_t pc;
    uintptr_t sp;
    uintptr_t lr;
    pid_t tid;
} crash_context_snapshot_t;

static int signal_stack_fd = -1;

/* Async-signal-safe builders. They never allocate and always leave a NUL when cap > 0. */
static size_t append_char(char* buf, size_t cap, size_t len, char ch) {
    if(buf == NULL || cap == 0 || len >= cap) {
        return len;
    }
    if(len + 1 < cap) {
        buf[len] = ch;
        buf[len + 1] = '\0';
        return len + 1;
    }
    buf[len] = '\0';
    return len;
}

static size_t append_text(char* buf, size_t cap, size_t len, const char* text) {
    if(text == NULL) {
        return len;
    }
    while(*text != '\0') {
        size_t next = append_char(buf, cap, len, *text);
        if(next == len) {
            return len;
        }
        len = next;
        text++;
    }
    return len;
}

static size_t append_int(char* buf, size_t cap, size_t len, int value) {
    char digits[16];
    unsigned int magnitude;
    size_t count = 0;

    if(value < 0) {
        len = append_char(buf, cap, len, '-');
        magnitude = (unsigned int)(-(value + 1)) + 1u;
    } else {
        magnitude = (unsigned int)value;
    }
    do {
        digits[count++] = (char)('0' + (magnitude % 10u));
        magnitude /= 10u;
    } while(magnitude != 0u && count < sizeof(digits));
    while(count > 0) {
        count--;
        len = append_char(buf, cap, len, digits[count]);
    }
    return len;
}

static size_t append_uintptr_hex(char* buf, size_t cap, size_t len, uintptr_t value) {
    char digits[2 * sizeof(uintptr_t)];
    size_t count = 0;
    static const char hex[] = "0123456789abcdef";

    len = append_text(buf, cap, len, "0x");
    if(value == 0) {
        return append_char(buf, cap, len, '0');
    }
    while(value != 0 && count < sizeof(digits)) {
        digits[count++] = hex[value & 0xfu];
        value >>= 4;
    }
    while(count > 0) {
        count--;
        len = append_char(buf, cap, len, digits[count]);
    }
    return len;
}

static size_t format_abort_report(
    char* buf,
    size_t cap,
    int signal_id,
    const char* signal_label,
    int si_code,
    uintptr_t fault_address,
    int tid,
    uintptr_t pc,
    uintptr_t sp,
    uintptr_t lr
) {
    size_t len = 0;
    if(buf != NULL && cap > 0) {
        buf[0] = '\0';
    }
    if(signal_label == NULL) {
        signal_label = "UNKNOWN";
    }
    len = append_text(buf, cap, len, "signal=");
    len = append_text(buf, cap, len, signal_label);
    len = append_char(buf, cap, len, '(');
    len = append_int(buf, cap, len, signal_id);
    len = append_text(buf, cap, len, ")\nsi_code=");
    len = append_int(buf, cap, len, si_code);
    len = append_text(buf, cap, len, "\nsi_addr=");
    len = append_uintptr_hex(buf, cap, len, fault_address);
    len = append_text(buf, cap, len, "\ntid=");
    len = append_int(buf, cap, len, tid);
    len = append_text(buf, cap, len, "\npc=");
    len = append_uintptr_hex(buf, cap, len, pc);
    len = append_text(buf, cap, len, "\nsp=");
    len = append_uintptr_hex(buf, cap, len, sp);
    len = append_text(buf, cap, len, "\nlr=");
    len = append_uintptr_hex(buf, cap, len, lr);
    len = append_char(buf, cap, len, '\n');
    return len;
}

static crash_context_snapshot_t capture_crash_context_snapshot(void* ucontext) {
    crash_context_snapshot_t snapshot;
    snapshot.pc = 0;
    snapshot.sp = 0;
    snapshot.lr = 0;
    snapshot.tid = (pid_t)syscall(__NR_gettid);
    if(ucontext == NULL) {
        return snapshot;
    }
    ucontext_t* context = (ucontext_t*)ucontext;
#if defined(__aarch64__)
    snapshot.pc = (uintptr_t)context->uc_mcontext.pc;
    snapshot.sp = (uintptr_t)context->uc_mcontext.sp;
    snapshot.lr = (uintptr_t)context->uc_mcontext.regs[30];
#elif defined(__arm__)
    snapshot.pc = (uintptr_t)context->uc_mcontext.arm_pc;
    snapshot.sp = (uintptr_t)context->uc_mcontext.arm_sp;
    snapshot.lr = (uintptr_t)context->uc_mcontext.arm_lr;
#elif defined(__x86_64__)
    snapshot.pc = (uintptr_t)context->uc_mcontext.gregs[REG_RIP];
    snapshot.sp = (uintptr_t)context->uc_mcontext.gregs[REG_RSP];
    snapshot.lr = 0;
#elif defined(__i386__)
    snapshot.pc = (uintptr_t)context->uc_mcontext.gregs[REG_EIP];
    snapshot.sp = (uintptr_t)context->uc_mcontext.gregs[REG_ESP];
    snapshot.lr = 0;
#endif
    return snapshot;
}

static const char* signal_name(int signal_id) {
    switch(signal_id) {
        case SIGABRT: return "SIGABRT";
        case SIGSEGV: return "SIGSEGV";
        case SIGBUS: return "SIGBUS";
        case SIGILL: return "SIGILL";
        case SIGFPE: return "SIGFPE";
        default: return "UNKNOWN";
    }
}

static void setup_signal_stack_report_file() {
    if(signal_stack_fd != -1) {
        close(signal_stack_fd);
        signal_stack_fd = -1;
    }
    signal_stack_fd = open(
        SIGNAL_DUMP_FILE_NAME,
        O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC,
        0644
    );
    if(signal_stack_fd == -1) {
        return;
    }
}

static void abort_waiter_handler(int signal, siginfo_t* info, void* ucontext) {
    char report[512];
    crash_context_snapshot_t context_snapshot = capture_crash_context_snapshot(ucontext);
    int si_code = 0;
    uintptr_t fault_address = 0;
    size_t len;
    if(info != NULL) {
        si_code = info->si_code;
        fault_address = (uintptr_t)info->si_addr;
    }
    len = format_abort_report(
        report,
        sizeof(report),
        signal,
        signal_name(signal),
        si_code,
        fault_address,
        (int)context_snapshot.tid,
        context_snapshot.pc,
        context_snapshot.sp,
        context_snapshot.lr
    );
    if(signal_stack_fd != -1 && len > 0) {
        write(signal_stack_fd, report, len);
        fsync(signal_stack_fd);
    }
    // IMPORTANT: re-raise with default handling so debuggerd/JVM can emit full fatal stack logs.
    raise(signal);
    _exit(128 + signal);
}

static void abort_waiter_setup() {
    // Track common fatal signals and immediately forward to default handler
    // after writing minimal metadata.
    const static int tracked_signals[] = {SIGABRT, SIGSEGV, SIGBUS, SIGILL, SIGFPE};
    const static int ntracked = (sizeof(tracked_signals) / sizeof(tracked_signals[0]));
    struct sigaction sigactions[ntracked];
    memset(sigactions, 0, sizeof(sigactions));
    for(size_t i = 0; i < ntracked; i++) {
        sigemptyset(&sigactions[i].sa_mask);
        sigactions[i].sa_sigaction = abort_waiter_handler;
        // SA_RESETHAND + SA_NODEFER allows immediate re-raise to hit default disposition.
        sigactions[i].sa_flags = SA_SIGINFO | SA_RESETHAND | SA_NODEFER;
    }
    for(size_t i = 0; i < ntracked; i++) {
        if(sigaction(tracked_signals[i], &sigactions[i], NULL) != 0) {
            printf("Failed to set signal hander for signal %zu: %s", i, strerror(errno));
        }
    }
}

static jint launchJVM(int margc, char** margv) {
   void* libjli = dlopen("libjli.so", RTLD_LAZY | RTLD_GLOBAL);

   // Preserve bionic's reserved runtime handlers, including libcore's signal 34.
   struct sigaction clean_sa;
   memset(&clean_sa, 0, sizeof (struct sigaction));
   for(int sigid = SIGHUP; sigid < NSIG; sigid++) {
       if(sigid >= __SIGRTMIN && sigid <= __SIGRTMIN + 9) {
           continue;
       }
       clean_sa.sa_handler = SIG_DFL;
       sigaction(sigid, &clean_sa, NULL);
   }
   // Set up the thread that will abort the launcher with an user-facing dialog on a signal.
   setup_signal_stack_report_file();
   abort_waiter_setup();

   // Boardwalk: silence
   // ;
   if (NULL == libjli) {
       ;
       return -1;
   }
   ;

   JLI_Launch_func *pJLI_Launch =
          (JLI_Launch_func *)dlsym(libjli, "JLI_Launch");
    // Boardwalk: silence
    // ;

   if (NULL == pJLI_Launch) {
       ;
       return -1;
   }

   ;

   return pJLI_Launch(margc, margv,
                   0, NULL, // sizeof(const_jargs) / sizeof(char *), const_jargs,
                   0, NULL, // sizeof(const_appclasspath) / sizeof(char *), const_appclasspath,
                   FULL_VERSION,
                   DOT_VERSION,
                   *margv, // (const_progname != NULL) ? const_progname : *margv,
                   *margv, // (const_launcher != NULL) ? const_launcher : *margv,
                   (const_jargs != NULL) ? JNI_TRUE : JNI_FALSE,
                   const_cpwildcard, const_javaw, const_ergo_class);
/*
   return pJLI_Launch(argc, argv, 
       0, NULL, 0, NULL, FULL_VERSION,
       DOT_VERSION, *margv, *margv, // "java", "openjdk",
       JNI_FALSE, JNI_TRUE, JNI_FALSE, 0);
*/
}

/*
 * Class:     com_oracle_dalvik_VMLauncher
 * Method:    launchJVM
 * Signature: ([Ljava/lang/String;)I
 */
JNIEXPORT jint JNICALL Java_com_oracle_dalvik_VMLauncher_launchJVM(JNIEnv *env, jclass clazz, jobjectArray argsArray) {

   jint res = 0;

    if (argsArray == NULL) {
        ;
        //handle error
        return 0;
    }

    int argc = (*env)->GetArrayLength(env, argsArray);
    char **argv = convert_to_char_array(env, argsArray);

    ;
    if (argv == NULL) {
        fprintf(stderr, "launchJVM: failed to copy launch arguments\n");
        return -1;
    }

    res = launchJVM(argc, argv);

    ;
    free_char_array(env, argsArray, (const char **)argv);

    ;

    return res;
}
