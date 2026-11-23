#!/usr/bin/env python3
"""Restore the encrypted signing bundle without displaying credentials.

Requires Python 3, GitHub CLI authenticated for the private vault, and GnuPG.
"""
import base64
import io
import json
import os
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPO = "lwtor/private-vault"
BASE = "projects/venera-native"
FILES = {
    "signing/venera-native-release-vault.keystore": ROOT / "signing/venera-native-release-vault.keystore",
    "keystore-vault.properties": ROOT / "keystore-vault.properties",
}


def github_json(path):
    result = subprocess.run(["gh", "api", path], check=True, capture_output=True)
    return json.loads(result.stdout)


def vault_file(name):
    response = github_json(f"repos/{REPO}/contents/{BASE}/{name}")
    if response.get("encoding") != "base64":
        raise ValueError("Vault file is not available as base64 content")
    return base64.b64decode(response["content"], validate=False)


def main():
    if not github_json(f"repos/{REPO}").get("private"):
        raise ValueError("Vault repository is not private")
    encrypted = vault_file("release-signing.gpg")
    password = vault_file("backup-password.txt").strip()
    with tempfile.TemporaryDirectory(prefix="venera-signing-") as temporary:
        archive = Path(temporary) / "release-signing.gpg"
        archive.write_bytes(encrypted)
        result = subprocess.run(
            ["gpg", "--batch", "--pinentry-mode", "loopback", "--passphrase-fd", "0",
             "--output", "-", "--decrypt", str(archive)],
            input=password + b"\n", capture_output=True, check=True,
        )
    with zipfile.ZipFile(io.BytesIO(result.stdout)) as bundle:
        if set(bundle.namelist()) != set(FILES):
            raise ValueError("Unexpected files in signing bundle")
        content = {name: bundle.read(name) for name in FILES}
    for name, destination in FILES.items():
        if destination.exists() and destination.read_bytes() != content[name]:
            raise ValueError(f"Refusing to overwrite existing file: {destination.name}")
    for name, destination in FILES.items():
        destination.parent.mkdir(parents=True, exist_ok=True)
        if not destination.exists():
            descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "wb") as output:
                output.write(content[name])
    print("Signing files restored. Release builds verify the pinned certificate.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError, zipfile.BadZipFile):
        print("Signing restore failed. Check GitHub authentication, private vault access, GnuPG, and existing files.", file=sys.stderr)
        sys.exit(1)
