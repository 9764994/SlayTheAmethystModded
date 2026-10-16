"""Build host JNI fixtures and run them with bounded execution time."""
import os
from pathlib import Path
import shlex
import shutil
import subprocess


def main():
    root = Path(__file__).resolve().parents[4]
    javac = shutil.which("javac")
    if not os.environ.get("JAVA_HOME") and javac is None:
        raise SystemExit("A JDK is required: set JAVA_HOME or put javac on PATH")
    java_home = Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else Path(javac).resolve().parent.parent
    out = root / "agent-tmp/jni-regression"
    classes = out / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    cc = shlex.split(os.environ.get("CC", "gcc"))
    common = cc + ["-D_GNU_SOURCE", "-std=gnu17", "-O1", "-g", "-Wno-incompatible-pointer-types",
                   f"-I{java_home}/include", f"-I{java_home}/include/linux",
                   "-Iapp/src/main/jni", "-Iscripts/tests/jni/include", "-pthread"]

    def run(command, env=None):
        subprocess.run([str(arg) for arg in command], cwd=root, env=env, check=True, timeout=60)

    for name in ("clipboard", "exec_env", "awt_clipboard"):
        run(common + ["-fPIC", "-shared", "-ffunction-sections", "-fdata-sections",
                      f"scripts/tests/jni/{name}_test.c", "-Wl,--gc-sections", "-lm", "-ldl",
                      "-o", out / f"{name}.so"])
    run(common + ["-fsanitize=address,undefined", "scripts/tests/jni/awt_cache_test.c",
                  "-o", out / "awt-cache-test"])
    sources = sorted((root / "scripts/tests/jni").glob("*.java"))
    sources.append(root / "scripts/tests/jni/org/lwjgl/glfw/CallbackBridge.java")
    run([java_home / "bin/javac", "-d", classes, *sources])
    for test, library in (("ClipboardTest", "clipboard"), ("ExecEnvTest", "exec_env"),
                          ("AwtClipboardTest", "awt_clipboard")):
        classpath = str(classes)
        if test == "AwtClipboardTest":
            classpath += os.pathsep + str(root / "app/src/main/assets/components/caciocavallo/cacio-androidnw-1.10-SNAPSHOT.jar")
        run([java_home / "bin/java", "-Xcheck:jni", "-cp", classpath, test, out / f"{library}.so"])
    env = dict(os.environ, ASAN_OPTIONS="halt_on_error=1:detect_leaks=1")
    run([out / "awt-cache-test"], env=env)
