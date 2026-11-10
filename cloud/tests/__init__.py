"""Tests for cloud/server and cloud/bot. Run from cloud/: python3 -m unittest"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
for sub in ("server", "bot"):
    sys.path.insert(0, os.path.join(HERE, "..", sub))
