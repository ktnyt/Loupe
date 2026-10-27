#!/usr/bin/env python3
"""Append structured progress events to GitHub issues; standard library only."""
import argparse
import datetime as dt
import json
import subprocess
import sys
import uuid

REPO = 'ktnyt/Loupe'
MARKER = 'loupe-progress:v1 '
PHASES = {'implementation': '実装（着手）', 'install': 'インストール',
          'device-test': '実機検証', 'additional': '追加開発',
          'design': '目的・契約設計', 'baseline-test': '変更前の実機確認',
          'claude-design': 'Claude UI設計', 'ui-review': 'UI・UX独立レビュー',
          'cold-review': '独立コードレビュー', 'security-review': 'セキュリティレビュー',
          'delivery': 'PR・マージ'}
STATUSES = {'start': '開始', 'complete': '完了', 'fail': '失敗',
            'cancel': '中断', 'skip': '省略'}


def api(path, payload=None):
    cmd = ['gh', 'api', path]
    if payload is not None:
        cmd += ['--method', 'POST', '--input', '-']
    result = subprocess.run(cmd, input=json.dumps(payload) if payload else None,
                            text=True, capture_output=True, check=True)
    return json.loads(result.stdout)


def events(issue):
    result = subprocess.run(['gh', 'api', '--paginate', '--slurp',
                             f'repos/{REPO}/issues/{issue}/comments?per_page=100'],
                            text=True, capture_output=True, check=True)
    found = []
    for page in json.loads(result.stdout):
        for comment in page:
            for line in comment['body'].splitlines():
                if line.startswith('<!-- ' + MARKER) and line.endswith(' -->'):
                    found.append(json.loads(line[len('<!-- ' + MARKER):-4]))
    return found


def validate(history, event):
    same = [e for e in history if all(e[k] == event[k] for k in ('run', 'phase', 'attempt'))]
    if event['status'] in ('start', 'skip'):
        if same:
            raise ValueError('この工程・試行は記録済みです。再試行には新しい --attempt を使ってください。')
    elif len(same) != 1 or same[0]['status'] != 'start':
        raise ValueError('終了記録には、同じ run・phase・attempt の未終了の開始記録が必要です。')


def report(history, run):
    groups = {}
    for event in history:
        if event['run'] == run:
            groups.setdefault((event['phase'], event['attempt']), []).append(event)
    for (phase, attempt), rows in sorted(groups.items()):
        start = next((e for e in rows if e['status'] == 'start'), None)
        end = next((e for e in rows if e['status'] != 'start'), None)
        seconds = (dt.datetime.fromisoformat(end['at']) - dt.datetime.fromisoformat(start['at'])).total_seconds() if start and end else None
        print(json.dumps({'phase': phase, 'attempt': attempt,
                          'status': end['status'] if end else 'unfinished',
                          'elapsed_seconds': seconds}, ensure_ascii=False))
    return bool(groups) and all(any(e['status'] != 'start' for e in rows) for rows in groups.values())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['record', 'report'])
    parser.add_argument('--issue', required=True, type=int)
    parser.add_argument('--run', required=True, help='作業単位の一意なID。再開時も同じ値を使用')
    parser.add_argument('--phase', choices=PHASES)
    parser.add_argument('--status', choices=STATUSES)
    parser.add_argument('--attempt', type=int, default=1)
    parser.add_argument('--note', help='作業内容・結果・根拠。秘密情報を含めない')
    parser.add_argument('--dry-run', action='store_true', help='投稿内容のみ生成。認証・遷移の検証はしない')
    parser.add_argument('--then', dest='after', nargs=argparse.REMAINDER, help='開始記録の投稿・再取得確認後だけ実行するコマンド。最後に指定。終了記録は別途行う')
    args = parser.parse_args()
    if args.after is not None and (not args.after or args.command != 'record' or args.status != 'start' or args.dry_run):
        parser.error('--then は dry-run 以外の開始記録だけに使い、実行するコマンドを指定してください')
    if args.issue < 1 or args.attempt < 1:
        parser.error('issue と attempt は正の整数です')
    if args.command == 'report':
        return 0 if report(events(args.issue), args.run) else 1
    if not args.phase or not args.status or not args.note:
        parser.error('record には --phase --status --note が必要です')
    event = dict(id=str(uuid.uuid4()), run=args.run, phase=args.phase,
                 attempt=args.attempt, status=args.status,
                 at=dt.datetime.now(dt.timezone(dt.timedelta(hours=9))).isoformat(timespec='seconds'))
    encoded = json.dumps(event, ensure_ascii=True).replace('--', '\\u002d\\u002d')
    body = (f"### {PHASES[args.phase]}：{STATUSES[args.status]}\n\n"
            f"- 日時（JST）: {event['at']}\n- 作業ID: {args.run}\n- 試行: {args.attempt}\n\n"
            f"{args.note}\n\n<!-- {MARKER}{encoded} -->")
    if args.dry_run:
        print(body)
        return 0
    issue = api(f'repos/{REPO}/issues/{args.issue}')
    if 'pull_request' in issue:
        raise ValueError('PRではなくissue番号を指定してください')
    validate(events(args.issue), event)
    posted = api(f'repos/{REPO}/issues/{args.issue}/comments', {'body': body})
    verified = api(f'repos/{REPO}/issues/comments/{posted["id"]}')
    if verified['body'] != body:
        raise ValueError('投稿後の再取得で本文が一致しません。issueを確認してください。')
    print(verified['html_url'], flush=True)
    if args.after is not None:
        return subprocess.run(args.after).returncode
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(f'記録失敗: {error}\n投稿結果が不明な場合はissueを再取得し、重複投稿を避けてください。', file=sys.stderr)
        sys.exit(1)
