"""直接编译实际 JNI/QuickJS，故障注入验证 UTF 资源及原生异步任务归属；无需 Gradle 或设备。

运行：python3 android/quickjs-android/tests/test_jni_utf_resources.py
依赖：Linux、GCC/G++、JDK（提供 jni.h）。构建产物自动清理。
"""

from pathlib import Path
import os
import resource
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
CASES = (
    "get", "getValue", "contains", "executeFunction", "registerJavaMethod", "set",
    "executeScript", "scriptNoFilename", "stringValue", "loader", "normalize",
    "moduleImport", "invalidModule", "unicode",
    "get:1", "getValue:1", "contains:1", "executeFunction:1",
    "registerJavaMethod:1", "set:1", "setString:2", "arrayString:1",
    "executeScript:1", "executeScript:2", "stringValue:1", "loader:1", "normalize:1",
    "loaderNew:1", "normalizeNew:1", "normalizeNew:2",
    "loaderCallback", "normalizeCallback", "loaderNull", "normalizeNull",
)

SCOPE_CASES = (
    "timers", "initializationTimers", "pendingThen", "queuedJobs", "pendingAwait",
    "pendingCatchFinally", "initializationAwait", "liveRequest", "staleCleanup",
    "nestedAwait", "thenableAwait", "asyncGenerator", "identities",
    "restoreAfterException", "manyReusesAndGc", "closeWithPendingJobs",
    "reversedTokenOrder", "initializationAdoption", "retiredAdoption",
)


class JniUtfResourcesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        for compiler in ("gcc", "g++", "javac"):
            if shutil.which(compiler) is None:
                raise RuntimeError(f"需要安装 {compiler} 才能运行 JNI host 回归")
        cls.build = tempfile.TemporaryDirectory(prefix="melora-qjs-utf-")
        cls.addClassCleanup(cls.build.cleanup)
        build = Path(cls.build.name)
        jdk = Path(os.environ.get("JAVA_HOME") or Path(shutil.which("javac")).resolve().parents[1])
        quickjs = ROOT / "src/main/cpp/quickjs"
        defines = ["-D_GNU_SOURCE", "-DCONFIG_BIGNUM",
                   f'-DCONFIG_VERSION="{(quickjs / "quickjs/VERSION").read_text().strip()}"']
        objects = []
        for name in ("quickjs", "cutils", "libregexp", "libunicode", "libbf"):
            target = build / f"{name}.o"
            subprocess.run(["gcc", "-std=gnu11", "-O0", "-g", *defines, "-c",
                            str(quickjs / f"quickjs/{name}.c"), "-o", str(target)], check=True)
            objects.append(str(target))
        cls.binary = build / "jni_utf_resources"
        subprocess.run(["g++", "-std=c++17", "-O0", "-g", *defines,
                        f"-I{jdk / 'include'}", f"-I{jdk / 'include/linux'}",
                        f"-I{quickjs}", str(ROOT / "tests/jni_utf_resources.cpp"),
                        *objects, "-lm", "-ldl", "-lpthread", "-o", str(cls.binary)], check=True)
        resource.setrlimit(resource.RLIMIT_CORE, (0, 0))


def resource_case(case):
    def test(self):
        run = subprocess.run([str(self.binary), case], text=True, capture_output=True, timeout=15)
        self.assertEqual(run.returncode, 0, f"{case}: {run.stdout}{run.stderr}")
    return test


for _case in CASES:
    setattr(JniUtfResourcesTest, "test_" + _case.replace(":", "_failure_"), resource_case(_case))


def scope_case(case):
    def test(self):
        run = subprocess.run([
            str(self.binary), "jobScope", case,
            str(ROOT.parent / "app/src/main/assets/lx-shim.js"),
            str(ROOT / "tests/job_scope.js"),
        ], text=True, capture_output=True, timeout=15)
        self.assertEqual(run.returncode, 0, f"{case}: {run.stdout}{run.stderr}")
    return test


for _case in SCOPE_CASES:
    setattr(JniUtfResourcesTest, "test_scope_" + _case, scope_case(_case))

if __name__ == "__main__":
    unittest.main(verbosity=2)
