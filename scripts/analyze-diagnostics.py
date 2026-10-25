#!/usr/bin/env python3
"""Summarize request timelines without treating bind as display completion."""
import argparse
import collections
import json
import pathlib


def analyze(paths):
    events, malformed = {}, 0
    for path in paths:
        for line in pathlib.Path(path).read_text().splitlines():
            try:
                e = json.loads(line)
                events[e['session'], e['seq']] = e
            except (ValueError, KeyError):
                malformed += 1
    ordered = sorted(events.values(), key=lambda e: (e['session'], e['ns'], e['seq']))
    groups = collections.defaultdict(list)
    for e in ordered:
        groups[e['session'], e['request']].append(e)
    def elapsed(rows, start, end):
        first = next((e['ns'] for e in rows if e['event'] == start), None)
        last = next((e['ns'] for e in rows if e['event'] == end), None)
        return round((last - first) / 1e6, 3) if first is not None and last is not None else None
    rows = []
    for (session, request), es in groups.items():
        source = next((e for e in es if e['event'] == 'image.request' and e['size'] == 1600), None)
        if source is None:
            continue
        burst = groups.get((session, source['burst']), [])
        opened = next((e for e in burst if e['event'] == 'burst.open'), None)
        committed = next((e for e in es if e['event'] == 'image.frame_commit'), None)
        # Only the first hero request after open is tap-to-first-frame.
        first = min((e['ns'] for e in ordered if e['session'] == session
                     and e['event'] == 'image.request' and e.get('burst') == source['burst']
                     and e.get('size') == 1600), default=None)
        rows.append(dict(session=session, request=request, count=opened.get('count') if opened else None,
                         format=source['format'], memory_hit=next((e['hit'] for e in es if e['event'] == 'image.memory'), None),
                         bind_ms=elapsed(es, 'image.request', 'image.bind'),
                         draw_ms=elapsed(es, 'image.request', 'image.draw'),
                         frame_commit_ms=elapsed(es, 'image.request', 'image.frame_commit'),
                         tap_to_first_frame_ms=round((committed['ns']-opened['ns'])/1e6, 3)
                         if committed and opened and source['ns'] == first else None,
                         failed=any(e['event'] == 'image.fail' for e in es),
                         discarded=any(e['event'] == 'image.discard' for e in es)))
    spans = []
    for (session, request), es in groups.items():
        for start, end, name in [('task.enqueue', 'task.start', 'queue'),
                                  ('repository.lock_wait', 'repository.lock_acquired', 'lock')]:
            ms = elapsed(es, start, end)
            if ms is not None:
                spans.append(dict(session=session, request=request, stage=name, ms=ms))
        for e in es:
            if e['event'].endswith('.start') and e['event'] != 'task.start':
                ms = elapsed(es, e['event'], e['event'][:-6] + '.end')
                if ms is not None:
                    spans.append(dict(session=session, request=request, stage=e['event'][:-6], ms=ms))
    return dict(events=len(ordered), malformed_lines=malformed,
                dropped=max((e.get('dropped', 0) for e in ordered), default=0),
                images=rows, longest_spans=sorted(spans, key=lambda s: s['ms'], reverse=True)[:30])

if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('files', nargs='+')
    args = p.parse_args()
    print(json.dumps(analyze(args.files), ensure_ascii=False, indent=2))
