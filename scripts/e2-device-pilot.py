#!/usr/bin/env python3
"""Stage two explicitly sized device photos and measure the Android E2 archive path."""
import argparse
from pathlib import Path
import re
import shlex
import subprocess
import sys

APP = "jp.nano.rawgallery"
FOLDER = "cache/e2-pilot"
RUNNER = APP + ".test/" + APP + ".ArchiveInstrumentation"
HOST = "s3.ap-northeast-1.idrivee2.com"


def run(args):
    if not re.fullmatch(r"[0-9a-f]{32}", args.run_id):
        raise ValueError("試行IDの形式が不正です。")
    adb = [args.adb, "-s", args.serial]

    def call(*command, **kwargs):
        result = subprocess.run(adb + list(command), capture_output=True, **kwargs)
        if result.returncode:
            raise RuntimeError("ADBの操作に失敗しました。")
        return result.stdout

    def shell(command):
        return call("shell", command).decode()

    def unlocked():
        policy = shell("dumpsys window policy")
        if ("showing=false" not in policy or "showing=true" in policy
                or "INTERACTIVE_STATE_AWAKE" not in policy):
            raise RuntimeError("Android端末のロック解除が必要です。")

    def private_command(command):
        return f"run-as {APP} sh -c " + shlex.quote(command)

    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,61}[a-z0-9]", args.bucket):
        raise ValueError("バケット名の形式が不正です。")
    values = [line.partition(":")[2].strip()
              for line in args.key_file.read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    if (len(values) != 4 or values[0] != HOST or not re.fullmatch(r"[A-Za-z0-9]{20}", values[2])
            or not re.fullmatch(r"[A-Za-z0-9+/=_-]{40}", values[3])):
        raise ValueError("東京リージョンのキー書式を確認してください。")
    payload = ("\n".join([args.bucket, values[2], values[3]]) + "\n").encode()
    unlocked()
    photos = []
    for ext, size in (("orf", args.raw_bytes), ("jpg", args.jpeg_bytes)):
        if not 0 < size <= 128 * 1048576:
            raise ValueError("試行素材のサイズが範囲外です。")
        matches = shell(f"find /sdcard/DCIM /sdcard/Pictures -maxdepth 4 -type f "
                        f"-iname '*.{ext}' -size {size}c").splitlines()
        if len(matches) != 1 or not matches[0].startswith(("/sdcard/DCIM/", "/sdcard/Pictures/")):
            raise RuntimeError("指定サイズの端末写真を一意に特定できません。")
        photos.append((ext, matches[0], size))

    # Refuse to take over another live pilot or a directory we did not create.
    shell(private_command("umask 077; mkdir " + FOLDER))
    try:
        for ext, path, size in photos:
            dest = f"{FOLDER}/sample.{ext}"
            with subprocess.Popen(adb + ["exec-out", "cat " + shlex.quote(path)],
                                  stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as source:
                call("shell", "-T", private_command("umask 077; cat > " + dest), stdin=source.stdout)
                source.stdout.close()
                if source.wait() != 0:
                    raise RuntimeError("端末写真の読み出しに失敗しました。")
            if shell(private_command("stat -c %s " + dest)).strip() != str(size):
                raise RuntimeError("端末写真のコピーサイズが一致しません。")
        unlocked()
        call("shell", "-T", private_command("umask 077; cat > " + FOLDER + "/credentials"),
             input=payload)
        output = shell(f"am instrument -w -e mode e2-archive -e e2-run {args.run_id} {RUNNER}")
        if not re.search(r"(?m)^(?:INSTRUMENTATION_RESULT: stream=)?PASS e2-archive\s*$", output):
            # Instrumentation sanitizes exceptions. Accept only the known source location/class lines.
            for line in output.splitlines():
                if re.fullmatch(r"[\w.]+Exception|\s+at [\w.$]+\([\w.]+\.java:\d+\)", line):
                    print(line)
            raise RuntimeError("Androidの転送試行がPASSを返しませんでした。")
        rows = re.findall(r"(?m)^(orf|jpg) bytes=(\d+) put_ms=(\d+) get_ms=(\d+) id=([0-9a-f]{32})$", output)
        if len(rows) != 2 or [r[0] for r in rows] != ["orf", "jpg"]:
            raise RuntimeError("2枚分の計測出力を確認できません。")
        for ext, size, put, get, ident in rows:
            print(f"{ext} bytes={size} put_ms={put} get_ms={get} id={ident}")
    finally:
        shell(private_command("rm -f " + FOLDER + "/credentials "
                              + FOLDER + "/sample.orf " + FOLDER + "/sample.jpg; rmdir " + FOLDER))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--run-id", required=True, help="失敗後の再試行でも同じ32桁のIDを使用")
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--key-file", type=Path, required=True)
    parser.add_argument("--raw-bytes", type=int, required=True)
    parser.add_argument("--jpeg-bytes", type=int, required=True)
    try:
        run(parser.parse_args())
    except (OSError, ValueError, RuntimeError) as exc:
        print(str(exc) if isinstance(exc, RuntimeError) else "試行設定を読み込めません。", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
