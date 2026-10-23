"""Exercise the real C++ mixer on the host without an Android audio device."""
import pathlib
import re
import subprocess
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]


class SlingAudioTest(unittest.TestCase):
    def test_native_mixer(self):
        out = ROOT / "agent-tmp/sling-audio-host"
        (out / "aaudio").mkdir(parents=True, exist_ok=True)
        source = (ROOT / "app/src/main/jni/sling_audio.cpp").read_text()
        constants = sorted(set(re.findall(r"\bAAUDIO_[A-Z_]+", source)))
        functions = sorted(set(re.findall(r"\bAAudio\w+(?=\()", source)))
        # Platform calls are never used by these DSP tests. Stub only the ABI;
        # the tested Engine/render/consume code is included from production.
        header = "#pragma once\nstruct AAudioStream {}; struct AAudioStreamBuilder {};\n"
        header += "using aaudio_result_t=int; using aaudio_stream_state_t=int; using aaudio_data_callback_result_t=int;\n"
        header += "\n".join(f"constexpr int {name}={i};" for i, name in enumerate(constants))
        header += "\n" + "\n".join(f"template<class... T> int {name}(T...) {{ return 0; }}" for name in functions)
        (out / "aaudio/AAudio.h").write_text(header)
        javac = pathlib.Path(subprocess.check_output(["which", "javac"], text=True).strip()).resolve()
        include = javac.parents[1] / "include"
        executable = out / "test"
        subprocess.run(["g++", "-std=c++17", "-O1", "-g", "-fsanitize=address,undefined",
                        "-I" + str(out), "-I" + str(include), "-I" + str(include / "linux"),
                        str(ROOT / "scripts/tools/tests/sling_audio_test.cpp"), "-o", str(executable), "-ldl", "-pthread"], check=True)
        subprocess.run([str(executable)], check=True)


if __name__ == "__main__":
    unittest.main()
