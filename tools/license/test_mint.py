import unittest

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

import mint


class MintTest(unittest.TestCase):
    def test_a_minted_key_has_three_parts_and_a_verifiable_signature(self):
        private = Ed25519PrivateKey.from_private_bytes(bytes(range(32)))
        key = mint.mint(private, "home", ["ab12cd34"], "20271231")
        prefix, body, sig = key.split(".")
        self.assertEqual("TVH1", prefix)
        import base64
        pad = lambda s: s + "=" * (-len(s) % 4)
        payload = base64.urlsafe_b64decode(pad(body))
        self.assertEqual(b"tier=home;exp=20271231;dev=ab12cd34", payload)
        private.public_key().verify(base64.urlsafe_b64decode(pad(sig)), payload)

    def test_bad_input_is_refused(self):
        private = Ed25519PrivateKey.generate()
        for args in (("free", ["ab12cd34"]), ("home", []), ("home", ["xyz"]), ("home", ["ab12cd34"] * 6)):
            with self.assertRaises(ValueError):
                mint.mint(private, *args)

    def test_the_device_id_is_eight_hex_digits(self):
        d = mint.device_id("0123456789abcdef")
        self.assertEqual(8, len(d))
        int(d, 16)


if __name__ == "__main__":
    unittest.main()
