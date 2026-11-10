"""install/install-mytv.sh against a fake adb: what it asks the TV, and what it does when the TV refuses."""
import os
import re
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SH = ROOT / "install" / "install-mytv.sh"
PS1 = ROOT / "install" / "install-mytv.ps1"

FAKE_ADB = r"""#!/bin/sh
echo "$@" >> "$FAKE_LOG"
case "$*" in
  *get-state*) echo device ;;
  *getprop*) echo "${FAKE_SDK:-30}" ;;
  *" install "*) echo "${FAKE_INSTALL:-Success}" ;;
  *"shell sh "*) printf 'mytv-restore-granted\r\nmytv-service-bound\r\nmytv-home-ours\r\n' ;;
esac
exit 0
"""


def tv_script(path):
    text = Path(path).read_text()
    m = re.search(r"^#--- TV script begin$.*?^#--- TV script end$", text, re.S | re.M)
    assert m, path
    return m.group(0)


class InstallScriptTest(unittest.TestCase):
    def run_installer(self, **env):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        d = Path(tmp.name)
        (d / "bin").mkdir()
        adb = d / "bin" / "adb"
        adb.write_text(FAKE_ADB)
        adb.chmod(adb.stat().st_mode | stat.S_IEXEC)
        apk = d / "MyTV.apk"
        apk.write_bytes(b"apk")
        log = d / "adb.log"
        e = {"PATH": f"{d / 'bin'}:/usr/bin:/bin", "HOME": str(d), "FAKE_LOG": str(log), "MYTV_APK": str(apk)}
        e.update(env)
        p = subprocess.run(["bash", str(SH), "10.0.0.7"], env=e, capture_output=True, text=True, timeout=60, cwd=d)
        return p, log.read_text() if log.exists() else ""

    def test_installs_and_sets_everything_up(self):
        p, log = self.run_installer()
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn("connect 10.0.0.7:5555", log)
        self.assertIn("-s 10.0.0.7:5555 install -r", log)
        self.assertIn("push", log)
        self.assertIn("shell sh /data/local/tmp/mytv-setup.sh", log)
        self.assertIn("Key helper: running.", p.stdout)
        self.assertIn("Home button: opens MyTV.", p.stdout)
        self.assertNotIn("WARNING", p.stdout)

    def test_old_tv_is_refused_before_installing(self):
        p, log = self.run_installer(FAKE_SDK="23")
        self.assertEqual(p.returncode, 1)
        self.assertNotIn(" install ", log)

    def test_different_signature_is_explained_and_nothing_else_runs(self):
        p, log = self.run_installer(FAKE_INSTALL="Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]")
        self.assertEqual(p.returncode, 1)
        self.assertIn("different signature", p.stderr)
        self.assertNotIn("mytv-setup", log)

    def test_the_tv_script_is_the_same_in_both_installers(self):
        self.assertEqual(tv_script(SH), tv_script(PS1))

    def test_the_tv_script_does_what_the_manual_steps_did(self):
        s = tv_script(SH)
        for part in ("pm grant $PKG android.permission.WRITE_SECURE_SETTINGS", "AUTO_START allow", "APP_AUTO_START allow",
                     "appops write-settings", "accessibility_enabled 1", "set-home-activity"):
            self.assertIn(part, s)
        self.assertNotIn("pm disable", s, "the installer must leave the old launcher alone")


if __name__ == "__main__":
    unittest.main()
