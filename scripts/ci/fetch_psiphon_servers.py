#!/usr/bin/env python3
"""Stage Psiphon's signed bootstrap list for Android builds.

The URL and public key are the ones used by the vendored Aether Psiphon client.
PattNG also fetches and verifies this list at build time. The generated asset is
not source code and is not committed; run this script before a local APK build.
"""
from __future__ import annotations

import base64
import hashlib
import json
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "third_party/Aether/aether/src/psiphon.rs"
ASSET = ROOT / "app/src/main/assets/psiphon_servers.dat"
MAX_COMPRESSED = 16 * 1024 * 1024
MAX_UNCOMPRESSED = 64 * 1024 * 1024


def settings(source: str) -> tuple[str, str]:
    url_match = re.search(r'const SERVER_LIST_URL: &str =\s*"([^"]+)";', source)
    key_match = re.search(r'const SERVER_LIST_SIGNATURE_KEY: &str = concat!\((.*?)\);', source, re.S)
    if not url_match or not key_match:
        raise ValueError("Aether's Psiphon server-list URL or signing key was not found")
    return url_match.group(1), "".join(re.findall(r'"([^"]*)"', key_match.group(1)))


def verify(packed: bytes, key: str, minimum_entries: int = 50) -> tuple[int, set[str]]:
    """Check Psiphon's zlib/JSON RSA-SHA256 package and inspect its regions."""
    if not packed or len(packed) > MAX_COMPRESSED:
        raise ValueError("server list is empty or exceeds the compressed size limit")
    decompressor = zlib.decompressobj()
    decoded = decompressor.decompress(packed, MAX_UNCOMPRESSED + 1)
    if len(decoded) > MAX_UNCOMPRESSED or not decompressor.eof or decompressor.unused_data:
        raise ValueError("server list is truncated, oversized, or has trailing data")
    package = json.loads(decoded)
    data = package["data"]
    if not isinstance(data, str):
        raise ValueError("server-list data is not text")
    digest = base64.b64decode(package["signingPublicKeyDigest"], validate=True)
    if digest != hashlib.sha256(key.encode("ascii")).digest():
        raise ValueError("server list names a different signing key")
    signature = base64.b64decode(package["signature"], validate=True)

    with tempfile.TemporaryDirectory(prefix="psiphon-verify-") as temp:
        directory = Path(temp)
        (directory / "key.pem").write_text(
            "-----BEGIN PUBLIC KEY-----\n" + "\n".join(key[i:i + 64] for i in range(0, len(key), 64))
            + "\n-----END PUBLIC KEY-----\n", encoding="ascii"
        )
        (directory / "signature").write_bytes(signature)
        (directory / "data").write_bytes(data.encode("utf-8"))
        result = subprocess.run(
            ["openssl", "dgst", "-sha256", "-verify", str(directory / "key.pem"),
             "-signature", str(directory / "signature"), str(directory / "data")],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
        )
        if result.returncode:
            raise ValueError("server-list RSA signature is invalid")

    entries = [line.strip() for line in data.splitlines() if line.strip()]
    if len(entries) < minimum_entries:
        raise ValueError(f"server list has only {len(entries)} entries")
    regions: set[str] = set()
    for entry in entries:
        try:
            fields = bytes.fromhex(entry).decode("utf-8").split(" ", 4)
            region = json.loads(fields[4]).get("region", "")
            if isinstance(region, str) and re.fullmatch(r"[A-Za-z]{2}", region.strip()):
                regions.add(region.strip().upper())
        except (ValueError, UnicodeError, IndexError, AttributeError):
            continue
    if not regions:
        raise ValueError("server list contains no recognizable exit regions")
    return len(entries), regions


def fetch(url: str) -> bytes:
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=90) as response:
                return response.read(MAX_COMPRESSED + 1)
        except (OSError, urllib.error.URLError) as error:
            if attempt == 2:
                raise OSError(f"could not download the Psiphon server list: {error}") from error
            time.sleep(3 * (attempt + 1))
    raise AssertionError("unreachable")


def main() -> int:
    try:
        url, key = settings(SOURCE.read_text(encoding="utf-8"))
        packed = fetch(url)
        count, regions = verify(packed, key)
        ASSET.parent.mkdir(parents=True, exist_ok=True)
        # Replace only after a successful verification; never leave a partial asset.
        with tempfile.NamedTemporaryFile(dir=ASSET.parent, prefix=".psiphon-", delete=False) as output:
            temp = Path(output.name)
            output.write(packed)
        try:
            temp.replace(ASSET)
        finally:
            temp.unlink(missing_ok=True)
        print(f"Psiphon: verified {count} server entries, {len(regions)} exit regions; staged {ASSET.relative_to(ROOT)}")
        return 0
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError, zlib.error) as error:
        print(f"Psiphon server-list preparation failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
