"""Our own strings (children's mode, setup, watch next) exist in every language we translate, with the same placeholders.

The languages are the ones the roadmap names (ru, uk, de, es, pt, tr, pl) plus Hebrew, whose Home layout has its own
gate in test_hebrew_resources. Other upstream languages fall back to English for these keys, as Android does.
"""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

RES = Path(__file__).resolve().parents[1] / "launcher/app/src/main/res"
LANGUAGES = ["values-ru", "values-uk", "values-de", "values-es", "values-pt", "values-tr", "values-pl", "values-iw"]
OURS = re.compile(r"^(kids_|setup_|sleep_|watch_next|cloud_|family_message_kicker$)")
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]")


def strings(folder):
    return {s.attrib["name"]: (s.text or "") for s in ET.parse(RES / folder / "strings.xml").getroot()
            if s.tag == "string" and s.get("translatable") != "false"}


class FamilyStringsTest(unittest.TestCase):
    def test_every_language_has_every_one_of_our_strings(self):
        default = {k: v for k, v in strings("values").items() if OURS.match(k)}
        self.assertGreater(len(default), 80)
        for folder in LANGUAGES:
            have = strings(folder)
            for key, text in default.items():
                with self.subTest(language=folder, string=key):
                    self.assertIn(key, have)
                    if key in have:
                        self.assertEqual(sorted(PLACEHOLDER.findall(text)), sorted(PLACEHOLDER.findall(have[key])))


if __name__ == "__main__":
    unittest.main()
