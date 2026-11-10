"""The donation links in res/values/support.xml and the words around their QR codes.

The two links are plain https addresses a QR code can hold (QrCode takes up to 106 bytes), and every language the launcher
ships has the four strings of the support screen, so the captions and the note are never missing in one of them.
"""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

RES = Path(__file__).resolve().parents[1] / "launcher/app/src/main/res"
KEYS = ["support_title", "support_text", "support_worldwide", "support_russia"]


def strings(folder, name="strings.xml"):
    return {s.attrib["name"]: (s.text or "") for s in ET.parse(RES / folder / name).getroot() if s.tag == "string"}


class SupportTest(unittest.TestCase):
    def test_the_default_links_are_https_and_fit_a_qr_code(self):
        links = strings("values", "support.xml")
        self.assertEqual(sorted(links), ["support_url", "support_url_ru"])
        for key, url in links.items():
            with self.subTest(link=key):
                self.assertTrue(url.startswith("https://"))
                self.assertNotRegex(url, r"\s")
                self.assertLessEqual(len(url.encode("utf-8")), 106)

    def test_every_language_has_the_four_support_strings(self):
        for folder in sorted(RES.glob("values*")):
            if not (folder / "strings.xml").exists():
                continue
            have = strings(folder.name)
            for key in KEYS:
                with self.subTest(folder=folder.name, string=key):
                    self.assertIn(key, have)
                    self.assertTrue(have[key].strip())

    def test_the_support_text_says_nothing_is_sent_and_has_no_placeholders(self):
        for folder in sorted(RES.glob("values*")):
            if not (folder / "strings.xml").exists():
                continue
            with self.subTest(folder=folder.name):
                self.assertNotIn("%", strings(folder.name)["support_text"])


if __name__ == "__main__":
    unittest.main()
