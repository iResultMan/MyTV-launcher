#!/usr/bin/env python3
"""Makes licence keys for the launcher (E7.1). The private key never goes into the repository.

  mint.py keygen --out ~/.config/tvhome/license-signing.key     # prints the public key (hex) to put in config.xml
  mint.py device --android-id <ANDROID_ID>                      # the id shown on the TV's setup screen, from a known ANDROID_ID
  mint.py mint --key ~/.config/tvhome/license-signing.key --tier home --devices ab12cd34,ef56ab78 [--exp 20271231]

A key is  TVH1.<base64url payload>.<base64url signature>  with the payload  tier=home;exp=20271231;dev=ab12cd34,ef56ab78
(see family/LicenseKey.java). Needs the `cryptography` package.
"""
import argparse
import base64
import hashlib
import os
import sys

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

TIERS = ("home", "family")


def b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def device_id(android_id):
    return hashlib.sha256(("tvhome:" + android_id).encode()).hexdigest()[:8]


def payload(tier, devices, exp=None):
    if tier not in TIERS:
        raise ValueError("tier must be one of %s" % (TIERS,))
    if not devices or len(devices) > 5:
        raise ValueError("1 to 5 device ids")
    for d in devices:
        if len(d) != 8 or any(c not in "0123456789abcdef" for c in d):
            raise ValueError("a device id is 8 hex digits: %r" % d)
    text = "tier=%s;" % tier + ("exp=%s;" % exp if exp else "") + "dev=" + ",".join(devices)
    return text.encode()


def mint(private, tier, devices, exp=None):
    body = payload(tier, devices, exp)
    return "TVH1.%s.%s" % (b64(body), b64(private.sign(body)))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    k = sub.add_parser("keygen")
    k.add_argument("--out", required=True)
    d = sub.add_parser("device")
    d.add_argument("--android-id", required=True)
    m = sub.add_parser("mint")
    m.add_argument("--key", required=True)
    m.add_argument("--tier", required=True)
    m.add_argument("--devices", required=True)
    m.add_argument("--exp")
    a = ap.parse_args()
    if a.cmd == "keygen":
        path = os.path.expanduser(a.out)
        if os.path.exists(path):
            sys.exit("refusing to overwrite " + path)
        private = Ed25519PrivateKey.generate()
        os.makedirs(os.path.dirname(path), exist_ok=True)
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as f:
            f.write(private.private_bytes(serialization.Encoding.Raw, serialization.PrivateFormat.Raw, serialization.NoEncryption()))
        public = private.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
        print("public key (hex):", public.hex())
    elif a.cmd == "device":
        print(device_id(a.android_id))
    else:
        raw = open(os.path.expanduser(a.key), "rb").read()
        print(mint(Ed25519PrivateKey.from_private_bytes(raw), a.tier, a.devices.split(","), a.exp))


if __name__ == "__main__":
    main()
