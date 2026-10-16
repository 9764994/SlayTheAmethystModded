# JNI host regressions

Run from Linux with GCC and a JDK (tested with OpenJDK 21):

```sh
python3 scripts/tests/jni/main.py
```

The tests include the production C implementations. Build outputs stay in
`agent-tmp/jni-regression/`; no Android installation or device changes occur.

- `ClipboardTest`: bounded NUL-terminated and unterminated UTF-8 input, empty
  text, supplementary characters, and first-argument extraction from argv.
  An identity Java callback stands in for Android's clipboard service.
- `ExecEnvTest`: inherited native environment (including a late native `setenv`),
  explicit empty environment, and explicit populated environment. Calls the real
  host JDK `ProcessEnvironment.environ` via the production JNI code.
- `AwtClipboardTest`: actual bundled Cacio clipboard wait/notify protocol, worker
  attach failure, pthread creation failure, and successful empty/text clipboard replies.
  The failure cases must throw rather than enter an unbounded wait.
- `awt_cache_test`: simulated JNI tables force concurrent cache initialization,
  failed-lookup retry and class-initialization reentry. Checks that all published
  references remain live and competing candidates are freed. ASan/UBSan enabled.

JNI/JVM tests run with `-Xcheck:jni`. The graphics headers in `include/` are
host-only type stubs for unrelated declarations; they are not used by the app.
These tests do not replace Android/dual-JVM CheckJNI testing or a native build.
