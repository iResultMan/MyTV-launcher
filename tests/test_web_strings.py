"""The phone pages and the weather block take their words from resources: English by default, Russian in values-ru.

KidsLockCore's two pages carry no language of their own: every S.web_* they read and every data-t / data-p they name
must be a string in values/ and in values-ru/ (same placeholders), and KidsLock must hand the whole table over. The
weather block's wx_* strings are held the same way.
"""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "launcher/app/src/main/java/com/gathin/tvhome"
RES = ROOT / "launcher/app/src/main/res"
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[a-zA-Z]|\{\d\}")


def strings(folder):
    return {s.attrib["name"]: (s.text or "") for s in ET.parse(RES / folder / "strings.xml").getroot() if s.tag == "string"}


class WebStringsTest(unittest.TestCase):
    def test_the_pages_read_only_strings_that_exist_in_english_and_russian(self):
        page = (JAVA / "KidsLockCore.java").read_text(encoding="utf-8")
        used = set(re.findall(r"S\.(web_[a-z0-9_]+)", page)) | set(re.findall(r"data-[tp]=(web_[a-z0-9_]+)", page))
        self.assertGreater(len(used), 30)
        en, ru = strings("values"), strings("values-ru")
        for key in sorted(used):
            with self.subTest(string=key):
                self.assertIn(key, en)
                self.assertIn(key, ru)
                if key in en and key in ru:
                    self.assertEqual(sorted(PLACEHOLDER.findall(en[key])), sorted(PLACEHOLDER.findall(ru[key])))

    def test_the_lock_hands_every_page_string_to_the_core(self):
        page = (JAVA / "KidsLockCore.java").read_text(encoding="utf-8")
        used = set(re.findall(r"S\.(web_[a-z0-9_]+)", page)) | set(re.findall(r"data-[tp]=(web_[a-z0-9_]+)", page))
        lock = (JAVA / "KidsLock.java").read_text(encoding="utf-8")
        handed = set(re.findall(r"R\.string\.(web_[a-z0-9_]+)", lock))
        self.assertEqual(sorted(used), sorted(handed))

    def test_every_weather_string_has_a_russian_text(self):
        weather = (JAVA / "WeatherHeader.java").read_text(encoding="utf-8")
        used = set(re.findall(r"R\.string\.(wx_[a-z0-9_]+)", weather))
        self.assertGreater(len(used), 10)
        en, ru = strings("values"), strings("values-ru")
        for key in sorted(used):
            with self.subTest(string=key):
                self.assertIn(key, en)
                self.assertIn(key, ru)


if __name__ == "__main__":
    unittest.main()
