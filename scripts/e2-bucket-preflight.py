#!/usr/bin/env python3
"""Read versioning and expiration settings on an explicitly selected Tokyo e2 bucket."""

import argparse
import json
import re
import subprocess
import sys

ENDPOINT = "https://s3.ap-northeast-1.idrivee2.com"


def read_config(profile, bucket, operation, missing=None):
    result = subprocess.run(
        ["aws", "--profile", profile, "--endpoint-url", ENDPOINT,
         "--region", "ap-northeast-1", "--no-cli-pager", "--output", "json",
         "--cli-connect-timeout", "10", "--cli-read-timeout", "15",
         "s3api", operation, "--bucket", bucket],
        capture_output=True, text=True, timeout=60, check=False,
    )
    if result.returncode != 0:
        match = re.search(r"An error occurred \(([A-Za-z0-9]+)\)", result.stderr)
        error = match.group(1) if match else None
        if missing and error == missing:
            return {"Rules": []}
        raise ValueError(f"{operation}: 読み取り失敗 ({error or 'AWS CLI エラー'})")
    value = json.loads(result.stdout)
    if not isinstance(value, dict):
        raise ValueError(f"{operation}: 応答形式が不正です")
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--bucket", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,61}[a-z0-9]", args.bucket):
        parser.error("バケット名の形式が不正です")
    try:
        versioning = read_config(args.profile, args.bucket, "get-bucket-versioning")
        if versioning.get("Status") != "Enabled":
            raise ValueError("バージョニングが有効ではありません")
        lifecycle = read_config(args.profile, args.bucket,
                                "get-bucket-lifecycle-configuration",
                                "NoSuchLifecycleConfiguration")
        rules = lifecycle.get("Rules")
        if not isinstance(rules, list) or any(
            not isinstance(rule, dict) or rule.get("Status") not in {"Enabled", "Disabled"}
            for rule in rules
        ):
            raise ValueError("ライフサイクル設定の応答形式が不正です")
        expiration_count = sum(
            rule["Status"] == "Enabled"
            and ("Expiration" in rule or "NoncurrentVersionExpiration" in rule)
            for rule in rules
        )
        if expiration_count:
            raise ValueError(f"有効な有効期限ルール {expiration_count} 件。"
                             "原本とカタログが適用範囲に含まれるか確認してください")
    except subprocess.TimeoutExpired:
        print("検査中断: AWS CLI の応答がタイムアウトしました", file=sys.stderr)
        return 1
    except (OSError, ValueError) as error:
        print(f"検査中断: {error}", file=sys.stderr)
        return 1
    print("バージョニング: 有効。失効を設定する有効なライフサイクルルール: 0件。")
    print("公開設定・端末キーの権限・保存版の読み戻しは別途確認が必要です。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
