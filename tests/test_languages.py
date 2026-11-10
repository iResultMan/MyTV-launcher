"""languages.json, the list Launcher settings reads from GitHub, names every language the build ships and is well formed.

The file may list more than the build holds (a translation added to the repository before the next release); the
launcher marks those as waiting for an update. It may not list fewer, or a build's own language would be missing from
its own list.
"""
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "launcher/app/src/main/res"
FOLDER_TAGS = {"b+es+419": "es-419", "b+sr+Latn": "sr-Latn", "in": "id", "iw": "he", "pt-rPT": "pt-PT",
               "zh-rCN": "zh-CN", "zh-rTW": "zh-TW"}


class LanguagesTest(unittest.TestCase):
    def setUp(self):
        self.languages = json.loads((ROOT / "languages.json").read_text(encoding="utf-8"))["languages"]

    def test_every_entry_has_a_plain_tag_and_a_name_and_none_repeats(self):
        tags = [entry["tag"] for entry in self.languages]
        self.assertEqual(len(tags), len(set(tags)))
        for entry in self.languages:
            with self.subTest(tag=entry["tag"]):
                self.assertRegex(entry["tag"], r"^[a-z]{2,3}(-[A-Za-z0-9]{1,8})*$")
                self.assertTrue(0 < len(entry["name"].strip()) <= 60)

    def test_every_language_of_the_build_is_listed(self):
        tags = {entry["tag"] for entry in self.languages}
        self.assertIn("en", tags)
        for folder in sorted(RES.glob("values-*")):
            name = folder.name[len("values-"):]
            if not re.match(r"^[a-z]{2,3}$|^b\+|^[a-z]{2,3}-r[A-Z]{2}$", name):
                continue   # a resource qualifier such as night or v21, not a language
            with self.subTest(folder=folder.name):
                self.assertIn(FOLDER_TAGS.get(name, name), tags)

    def test_the_launcher_reads_the_file_from_this_repository(self):
        source = (ROOT / "launcher/app/src/main/java/com/gathin/tvhome/LanguageSource.java").read_text(encoding="utf-8")
        self.assertRegex(source, r'DEFAULT_URL = "https://raw\.githubusercontent\.com/[^/]+/[^/]+/main/languages\.json"')


if __name__ == "__main__":
    unittest.main()
