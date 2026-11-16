#!/usr/bin/env python3
"""Offline checks for the build-time Psiphon list verifier."""
import base64
import hashlib
import json
import re
import subprocess
import tempfile
import unittest
import zlib
from pathlib import Path

import fetch_psiphon_servers as servers


class SignedPsiphonListTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        cls.private = cls.root / "private.pem"
        subprocess.run(
            ["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", str(cls.private)],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True,
        )
        public = subprocess.check_output(["openssl", "pkey", "-in", str(cls.private), "-pubout", "-outform", "DER"])
        cls.key = base64.b64encode(public).decode("ascii")

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def package(self, data, *, signed_data=None, key=None):
        payload = self.root / "entries"
        payload.write_bytes((signed_data if signed_data is not None else data).encode("utf-8"))
        signature = subprocess.check_output(["openssl", "dgst", "-sha256", "-sign", str(self.private), str(payload)])
        return zlib.compress(json.dumps({
            "data": data,
            "signingPublicKeyDigest": base64.b64encode(hashlib.sha256((key or self.key).encode("ascii")).digest()).decode(),
            "signature": base64.b64encode(signature).decode(),
        }).encode())

    @staticmethod
    def entry(region):
        return ("127.0.0.1 443 secret cert " + json.dumps({"region": region})).encode().hex()

    def test_verified_entries_supply_real_iso_region_fields(self):
        entries = self.entry("ca") + "\n" + self.entry("DE") + "\nnot-hex\n"
        count, regions = servers.verify(self.package(entries), self.key, minimum_entries=2)
        self.assertEqual(3, count)
        self.assertEqual({"CA", "DE"}, regions)

    def test_rejects_tampering_wrong_key_truncation_and_missing_regions(self):
        entries = self.entry("CA")
        with self.assertRaisesRegex(ValueError, "signature"):
            servers.verify(self.package(entries + "0", signed_data=entries), self.key, minimum_entries=1)
        with self.assertRaisesRegex(ValueError, "different signing key"):
            servers.verify(self.package(entries, key="other"), self.key, minimum_entries=1)
        with self.assertRaises(ValueError):
            servers.verify(self.package(entries)[:-3], self.key, minimum_entries=1)
        with self.assertRaisesRegex(ValueError, "no recognizable exit regions"):
            servers.verify(self.package(self.entry("not-a-country")), self.key, minimum_entries=1)
        with self.assertRaisesRegex(ValueError, "only 1 entries"):
            servers.verify(self.package(entries), self.key, minimum_entries=2)

    def test_url_and_key_match_the_vendored_client(self):
        url, key = servers.settings(servers.SOURCE.read_text(encoding="utf-8"))
        self.assertTrue(url.startswith("https://"))
        self.assertGreater(len(base64.b64decode(key)), 100)
        kotlin = (servers.ROOT / "app/src/main/java/com/manjaniq/core/PsiphonServerList.kt").read_text()
        match = re.search(r'internal const val SIGNING_KEY =\s*(.*?)\n\s*private const', kotlin, re.S)
        self.assertIsNotNone(match)
        self.assertEqual(key, "".join(re.findall(r'"([^"]*)"', match.group(1))))


if __name__ == "__main__":
    unittest.main()
