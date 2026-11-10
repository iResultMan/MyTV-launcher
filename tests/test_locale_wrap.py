"""Every screen and helper the launcher shows text from follows the launcher's language, not only the TV's.

The language switch (Launcher settings > Language, or a theme's locale) works by wrapping each component's base context
in LauncherLocale.wrap. A screen that forgets it keeps the TV's language beside screens that follow the switch (Check and
set up stayed in Russian while Launcher settings was in English). So every activity, the screensaver and the two
accessibility helpers in the manifest must wrap, except the two activities that never show anything.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1] / "launcher/app/src/main"
JAVA = ROOT / "java/com/gathin/tvhome"
SCREENS = re.compile(r"class \w+ extends (?:android\.app\.)?(?:Activity|(?:android\.service\.dreams\.)?DreamService|AccessibilityService)\b")
SHOWS_TEXT = re.compile(r"setContentView|Toast\.|getString\(|R\.string\.|Dialog|setText\(")
# Components with no screen of their own: a market:// intent trampoline and an invisible activity that closes at once.
NO_UI = {"AppLaunchActivity", "ProbeActivity"}


def components():
    names = re.findall(r'<(?:activity|service)[^>]*android:name="\.([A-Za-z0-9_]+)"', (ROOT / "AndroidManifest.xml").read_text(encoding="utf-8"))
    return sorted(set(names))


class LocaleWrapTest(unittest.TestCase):
    def test_every_component_that_shows_text_wraps_its_base_context(self):
        found = components()
        self.assertGreater(len(found), 12)
        for name in found:
            source = (JAVA / (name + ".java")).read_text(encoding="utf-8")
            if name in NO_UI or not SCREENS.search(source):
                continue   # a notification listener or the like: no text of its own
            with self.subTest(component=name):
                self.assertIn("LauncherLocale.wrap(base)", source, name + " does not follow the launcher's language")

    def test_the_components_left_out_really_show_nothing(self):
        for name in sorted(NO_UI):
            source = (JAVA / (name + ".java")).read_text(encoding="utf-8")
            with self.subTest(component=name):
                self.assertIsNone(SHOWS_TEXT.search(source), name + " shows text now: wrap its base context and take it off NO_UI")

    def test_the_components_found_in_the_manifest_include_the_ones_that_were_missing(self):
        found = set(components())
        for name in ("SetupActivity", "WatchNextActivity", "KidsSettingsActivity", "ClockDream", "HomeKeyService"):
            self.assertIn(name, found)


if __name__ == "__main__":
    unittest.main()
