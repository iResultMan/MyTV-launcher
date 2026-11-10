"""The strings of the Phone and cloud screen exist in every language the launcher ships, with the English placeholders.

This is the screen that shows what the server learns before cloud control can be turned on, so no language may fall back
to English for it. The translations are machine-made (README says so); this test only keeps them complete.
"""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

RES = Path(__file__).resolve().parents[1] / "launcher/app/src/main/res"
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]")


def cloud_strings(folder):
    return {s.attrib["name"]: (s.text or "") for s in ET.parse(RES / folder / "strings.xml").getroot()
            if s.tag == "string" and s.attrib["name"].startswith("cloud_")}


class CloudStringsTest(unittest.TestCase):
    def test_every_language_has_every_cloud_string_with_the_same_placeholders(self):
        english = cloud_strings("values")
        self.assertGreaterEqual(len(english), 24)
        folders = [f for f in sorted(RES.glob("values-*")) if (f / "strings.xml").exists()]
        self.assertGreaterEqual(len(folders), 30)
        for folder in folders:
            have = cloud_strings(folder.name)
            for key, text in english.items():
                with self.subTest(language=folder.name, string=key):
                    self.assertIn(key, have)
                    if key in have:
                        self.assertTrue(have[key].strip())
                        self.assertEqual(sorted(PLACEHOLDER.findall(text)), sorted(PLACEHOLDER.findall(have[key])))


if __name__ == "__main__":
    unittest.main()
