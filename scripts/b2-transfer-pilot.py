#!/usr/bin/env python3
"""Measure a small B2 round trip without touching an existing archive."""
import argparse
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
from urllib.parse import urlencode
import uuid

LIMIT = 256 * 1024 * 1024
RAW = {".dng", ".orf", ".arw", ".nef", ".raf", ".cr2", ".cr3", ".rw2"}
JPEG = {".jpg", ".jpeg"}
ENDPOINT = re.compile(r"(?:https://)?(s3\.([a-z]{2}-[a-z]+-\d{3})\.backblazeb2\.com)/?")
BUCKET = re.compile(r"[A-Za-z0-9-]{6,63}")
KEY_ID = re.compile(r"[A-Za-z0-9]{1,128}")
SECRET = re.compile(r"[A-Za-z0-9+/=_-]{1,256}")
VERSION = re.compile(r"[A-Za-z0-9_-]{1,512}")


def credentials():
    key_id = os.environ.get("B2_APPLICATION_KEY_ID")
    secret = os.environ.get("B2_APPLICATION_KEY")
    if key_id is None and secret is None:
        if not sys.stdin.isatty():
            raise ValueError("B2キーの入力には端末が必要です。")
        key_id = getpass.getpass("B2 keyID（非表示）: ")
        secret = getpass.getpass("B2 applicationKey（非表示）: ")
    if not KEY_ID.fullmatch(key_id or "") or not SECRET.fullmatch(secret or ""):
        raise ValueError("B2のキーの形式を確認してください。")
    return f'user = "{key_id}:{secret}"\n'


def fingerprint(path, limit, copy_to=None):
    size = 0
    digest = hashlib.sha256()
    with path.open("rb") as source:
        if not stat.S_ISREG(os.fstat(source.fileno()).st_mode):
            raise ValueError("通常のファイルを指定してください。")
        while chunk := source.read(1024 * 1024):
            size += len(chunk)
            if size > limit:
                raise ValueError("試行の容量上限を超えました。")
            digest.update(chunk)
            if copy_to is not None:
                copy_to.write(chunk)
    if not size:
        raise ValueError("空のファイルは試行できません。")
    return size, digest.hexdigest()


def transfer(curl, auth, region, url, target, headers, size, upload=None):
    command = [
        curl, "--disable", "--config", "-", "--silent", "--globoff",
        "--proto", "=https", "--connect-timeout", "15", "--max-time", "300",
        "--aws-sigv4", f"aws:amz:{region}:s3", "--dump-header", str(headers),
        "--output", str(target), "--max-filesize", str(65536 if upload else size),
        "--write-out", "%{http_code} %{time_total} %{size_upload} %{size_download}",
    ]
    if upload:
        path, sha = upload
        command += ["--header", f"x-amz-content-sha256: {sha}", "--upload-file", str(path)]
    else:
        command += ["--header", f"x-amz-content-sha256: {hashlib.sha256(b'').hexdigest()}"]
    command += ["--url", url]
    env = {k: v for k, v in os.environ.items() if not k.startswith("B2_APPLICATION_KEY")}
    result = subprocess.run(command, input=auth, capture_output=True, text=True, env=env)
    fields = result.stdout.split()
    status = fields[0] if len(fields) == 4 and fields[0].isdigit() else "不明"
    if result.returncode or status != "200":
        raise ValueError(f"転送に失敗しました（HTTP {status}、curl {result.returncode}）。")
    seconds = float(fields[1])
    transferred = int(fields[2 if upload else 3])
    if transferred != size:
        raise ValueError("転送バイト数が元のサイズと一致しません。")
    versions = re.findall(r"(?im)^x-amz-version-id:[ \t]*([^\r\n]+)", headers.read_text("latin-1"))
    if len(versions) != 1 or not VERSION.fullmatch(versions[0].strip()):
        raise ValueError("B2のオブジェクト版を確認できません。")
    return versions[0].strip(), seconds


def run(args):
    endpoint = ENDPOINT.fullmatch(args.endpoint)
    if not endpoint or not BUCKET.fullmatch(args.bucket):
        raise ValueError("B2のS3 Endpointまたはバケット名を確認してください。")
    formats = [p.suffix.lower() for p in args.photos]
    if (not 2 <= len(formats) <= 4 or set(formats) - (RAW | JPEG)
            or not set(formats) & RAW or not set(formats) & JPEG):
        raise ValueError("RAWとJPEGを各1枚以上、計2〜4枚指定してください。")
    if any(p.is_symlink() or not p.is_file() for p in args.photos):
        raise ValueError("通常のRAW/JPEGファイルを指定してください。")
    curl = shutil.which("curl")
    if not curl:
        raise ValueError("curlが見つかりません。")
    host, region = endpoint.groups()
    with tempfile.TemporaryDirectory(prefix="loupe-b2-pilot-") as folder:
        work = Path(folder)
        files = []
        total = 0
        for i, path in enumerate(args.photos, 1):
            snapshot = work / f"source-{i}"
            with snapshot.open("xb") as output:
                size, sha = fingerprint(path, LIMIT - total, output)
            total += size
            files.append((snapshot, size, sha))
        auth = credentials()
        prefix = "loupe-transfer-pilot/" + uuid.uuid4().hex
        print(json.dumps({"region": region, "prefix": prefix, "samples": len(files)}), flush=True)
        for i, ((snapshot, size, sha), suffix) in enumerate(zip(files, formats), 1):
            url = f"https://{host}/{args.bucket}/{prefix}/{i}{suffix}"
            headers = work / "headers"
            version, put = transfer(curl, auth, region, url, work / "response", headers,
                                    size, upload=(snapshot, sha))
            restored = work / "restored"
            query = urlencode({"versionId": version})
            fetched_version, get = transfer(curl, auth, region, f"{url}?{query}", restored, headers, size)
            if fetched_version != version or fingerprint(restored, size) != (size, sha):
                raise ValueError("取得した版またはサイズ・SHA-256が一致しません。")
            print(json.dumps({
                "sample": i, "format": suffix[1:], "bytes": size,
                "put_seconds": put, "get_seconds": get,
                "put_mib_per_s": round(size / 1048576 / put, 3) if put > 0 else None,
                "get_mib_per_s": round(size / 1048576 / get, 3) if get > 0 else None,
                "sha256_match": True,
            }), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--endpoint", required=True, help="B2 S3 Endpoint")
    parser.add_argument("--bucket", required=True, help="専用の非公開試験バケット")
    parser.add_argument("photos", nargs="+", type=Path)
    try:
        run(parser.parse_args())
        return 0
    except (ValueError, OSError) as error:
        message = str(error) if isinstance(error, ValueError) else "端末のファイル処理またはcurlの起動に失敗しました。"
        print(message, file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("試行を中断しました。", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
