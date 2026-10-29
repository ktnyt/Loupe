#!/usr/bin/env python3
"""Measure experimental editor input-to-frame commits with anonymous synthetic/RAW timing only."""
import argparse
import json
import math
from pathlib import Path
import re
import shlex
import statistics
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = 'jp.nano.rawgallery.rawexperiment'
ADB = '/opt/homebrew/share/android-commandlinetools/platform-tools/adb'


def select_mode(wait_prepared=False, require_preparation_overlap=False):
    if wait_prepared and require_preparation_overlap:
        raise ValueError('Preparation wait and preparation-overlap requirement are mutually exclusive')
    return 'after-prepared' if wait_prepared else 'require-preparation-overlap' if require_preparation_overlap else 'initial-preparation'


def parse_events(logs, minimum_ns=0, maximum_ns=None):
    """Parse only fixed anonymous fields; paths and arbitrary log text never enter results."""
    inputs = {}
    frames = []
    events = []
    def within(ns):
        return ns >= minimum_ns and (maximum_ns is None or ns <= maximum_ns)
    for line in logs.splitlines():
        match = re.search(r'adjust\.input ticket=(\d+) ns=(\d+)', line)
        if match:
            ticket, ns = map(int, match.groups())
            if within(ns):
                inputs[ticket] = ns
            continue
        match = re.search(r'adjust\.frame ticket=(\d+) input_ns=(\d+) commit_ns=(\d+)', line)
        if match:
            ticket, ns, committed = map(int, match.groups())
            if within(ns) and within(committed) and committed >= ns:
                frames.append({'ticket': ticket, 'input_ns': ns, 'commit_ns': committed})
            continue
        match = re.search(r'\b([a-z][a-z.]+) ms=(\d+) width=(\d+) height=(\d+) ns=(\d+)', line)
        if match:
            name, ms, width, height, ns = match.groups()
            if name in ('raw.load.complete', 'jpeg.load.complete', 'full.prepare.start', 'full.prepare.complete', 'adjust.render') and within(int(ns)):
                events.append({'name': name, 'ms': int(ms), 'width': int(width), 'height': int(height), 'ns': int(ns)})
    # A commit for a skipped or unrelated ticket is not a measured input sample.
    frames = [f for f in frames if inputs.get(f['ticket']) == f['input_ns']]
    return {'inputs': inputs, 'frames': frames, 'events': events}


def latency_metrics(inputs, frames):
    values = sorted((f['commit_ns'] - f['input_ns']) / 1e6 for f in frames)
    tickets = {f['ticket'] for f in frames}
    return {
        'input_count': len(inputs), 'frame_count': len(frames),
        'skipped_input_tickets': sum(ticket not in tickets for ticket in inputs),
        'median_ms': statistics.median(values) if values else None,
        'p95_ms': values[max(0, math.ceil(len(values) * .95) - 1)] if values else None,
        'commits_with_newer_input_during_latency': sum(
            any(f['input_ns'] < ns < f['commit_ns'] for ns in inputs.values()) for f in frames),
    }


def summarize(logs, mode, source, launch_ns=0, end_ns=None, drags=None):
    if mode not in ('initial-preparation', 'after-prepared', 'require-preparation-overlap'):
        raise ValueError('Invalid measurement mode')
    if source not in ('selected RAW', 'synthetic'):
        raise ValueError('Invalid source classification')
    parsed = parse_events(logs, launch_ns, end_ns)
    inputs, frames, events = parsed['inputs'], parsed['frames'], parsed['events']
    collected = latency_metrics(inputs, frames)
    if drags is not None:
        inputs = {ticket: ns for ticket, ns in inputs.items()
                  if any(b['start_ns'] <= ns <= b['end_ns'] for b in drags)}
        frames = [f for f in frames if f['ticket'] in inputs]
    first = lambda name: next((e for e in events if e['name'] == name), None)
    start, complete = first('full.prepare.start'), first('full.prepare.complete')
    start_ns = start['ns'] if start else None
    complete_ns = complete['ns'] if complete else None
    if complete_ns is not None and start_ns is not None and complete_ns < start_ns:
        complete_ns = None
        complete = None
    overlap_end = complete_ns if complete_ns is not None else end_ns
    def during(ns):
        return start_ns is not None and overlap_end is not None and start_ns <= ns < overlap_end
    def after(ns):
        return complete_ns is not None and ns >= complete_ns
    def subset(predicate):
        selected = {ticket: ns for ticket, ns in inputs.items() if predicate(ns)}
        return latency_metrics(selected, [f for f in frames if f['ticket'] in selected])
    metrics = latency_metrics(inputs, frames)
    overlap = subset(during)
    groups = []
    for bounds in drags or []:
        selected = {ticket: ns for ticket, ns in inputs.items() if bounds['start_ns'] <= ns <= bounds['end_ns']}
        commits = [f for f in frames if f['ticket'] in selected]
        last = max(selected, key=selected.get) if selected else None
        finals = [(f['commit_ns'] - selected[last]) / 1e6 for f in commits if f['ticket'] == last] if last is not None else []
        groups.append({
            'start_ns': bounds['start_ns'], 'end_ns': bounds['end_ns'],
            'input_count': len(selected), 'frame_count': len(commits),
            'final_ms': min(finals) if finals else None,
            'frames_while_dragging': sum(bounds['start_ns'] <= f['commit_ns'] <= bounds['end_ns'] for f in frames),
        })
    failures = []
    if not inputs or not frames:
        failures.append('No measured input/frame pairs')
    if drags is not None and (len(groups) != 6 or any(g['input_count'] == 0 for g in groups)):
        failures.append('Six drags with measured inputs were not observed')
    if mode == 'require-preparation-overlap':
        if start_ns is None or overlap['input_count'] < 100 or overlap['frame_count'] < 1:
            failures.append('Preparation overlap requires a known start, at least 100 inputs and one frame')
    if mode == 'after-prepared' and (complete_ns is None or any(ns < complete_ns for ns in inputs.values())):
        failures.append('After-prepared mode requires every measured input after preparation completion')
    return {
        'status': 'FAIL' if failures else 'PASS', 'failures': failures, 'mode': mode, 'source': source,
        'boundary': 'input to frame commit; not display scanout',
        'measurement': {'launch_ns': launch_ns, 'end_ns': end_ns},
        'preparation_classification': 'input timestamp inside preparation interval; frame commit may occur later',
        'preparation': {'start_ns': start_ns, 'complete_ns': complete_ns, 'observed_end_ns': overlap_end,
                        'completion_observed': complete is not None},
        'input_scope': 'six recorded drag windows' if drags is not None else 'collected event interval',
        'collected_interval': collected,
        'drag_boundary_clock': '/proc/uptime, 10ms resolution; input/frame timestamps use elapsedRealtimeNanos',
        'all': metrics, 'preparation_overlap': overlap, 'after_prepared': subset(after), 'drags': groups,
        'raw.load.complete': first('raw.load.complete'), 'full.prepare.complete': complete,
        'render_count': sum(e['name'] == 'adjust.render' for e in events),
    }


def parse_temperature(text):
    found = re.search(r'(?m)^\s*temperature:\s*(-?\d+)\s*$', text)
    return int(found.group(1)) / 10 if found else None


def parse_kernel_memory(text):
    return {key: int(m.group(1)) if (m := re.search(r'(?m)^' + field + r':\s*(\d+)\s+kB\s*$', text)) else None
            for key, field in [('rss_kb', 'VmRSS'), ('hwm_kb', 'VmHWM')]}


def parse_meminfo(text):
    result = {'native_heap_pss_kb': None, 'graphics_pss_kb': None, 'total_pss_kb': None}
    patterns = {'native_heap_pss_kb': r'(?m)^\s*Native Heap:\s*(\d+)',
                'graphics_pss_kb': r'(?m)^\s*Graphics:\s*(\d+)',
                'total_pss_kb': r'(?m)^\s*TOTAL PSS:\s*(\d+)'}
    for key, pattern in patterns.items():
        match = re.search(pattern, text)
        if match:
            result[key] = int(match.group(1))
    return result


class Device:
    def __init__(self, adb, serial):
        self.command = [adb, '-s', serial]
    def shell(self, *parts, timeout=20):
        try:
            result = subprocess.run(self.command + ['shell', shlex.join(parts)], capture_output=True,
                                    text=True, timeout=timeout, check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise RuntimeError('Device command unavailable or USB timeout') from None
        if result.returncode:
            raise RuntimeError('Device command failed')
        return result.stdout
    def unlocked(self):
        policy = self.shell('dumpsys', 'window', 'policy')
        if 'showing=false' not in policy or 'showing=true' in policy:
            raise RuntimeError('Device locked; measurement stopped')
    def clock(self):
        try:
            return int(float(self.shell('cat', '/proc/uptime').split()[0]) * 1e9)
        except (ValueError, IndexError):
            raise RuntimeError('Device monotonic clock unavailable') from None
    def xml(self):
        self.unlocked()
        self.shell('uiautomator', 'dump', '/data/local/tmp/loupe-full-profile-ui.xml')
        try:
            return ET.fromstring(self.shell('cat', '/data/local/tmp/loupe-full-profile-ui.xml'))
        except ET.ParseError:
            raise RuntimeError('Experimental UI state unavailable') from None


def parse_focus(text):
    """Return a fixed state only; never retain a window title or another app's identifier."""
    match = re.search(r'(?m)^\s*mCurrentFocus\s*=\s*(.*)$', text)
    if match is None or match.group(1).strip() in ('', 'null', 'None'):
        return 'unavailable'
    component = re.search(r'\b([A-Za-z][A-Za-z0-9_.]*)/[A-Za-z0-9_.$]+', match.group(1))
    if component is None:
        return 'unavailable'
    return 'experimental-editor' if component.group(1) == PACKAGE else 'other-app'


def bounds(node):
    values = list(map(int, re.findall(r'-?\d+', node.attrib.get('bounds', ''))))
    if len(values) != 4 or values[2] <= values[0] or values[3] <= values[1]:
        raise RuntimeError('Experimental UI bounds unavailable')
    return values


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', default=ADB)
    parser.add_argument('--output', required=True, type=Path, help='New local output directory')
    parser.add_argument('--uri-file', type=Path, help='Local authorized content URI; omit for synthetic')
    group = parser.add_mutually_exclusive_group()
    group.add_argument('--wait-prepared', action='store_true')
    group.add_argument('--require-preparation-overlap', action='store_true')
    args = parser.parse_args(argv)
    mode = select_mode(args.wait_prepared, args.require_preparation_overlap)
    uri = None
    if args.uri_file is not None:
        try:
            uri = args.uri_file.read_text().strip()
        except (OSError, UnicodeError):
            raise RuntimeError('Local URI input could not be read') from None
        if not uri.startswith('content://') or any(c in uri for c in '\r\n\x00'):
            raise RuntimeError('Local input must contain one content URI')
    try:
        args.output.mkdir(parents=True, exist_ok=False)
    except OSError:
        raise RuntimeError('A new local output directory is required') from None
    device = Device(args.adb, args.serial)
    device.unlocked()
    device.shell('input', 'keyevent', '127')
    device.shell('am', 'force-stop', 'com.google.android.youtube')
    activities = device.shell('dumpsys', 'activity', 'activities')
    if re.search(r'(?:windowingMode|mWindowingMode)\s*[=:]\s*(?:2\b|pinned\b)|mode=pinned\b', activities):
        raise RuntimeError('Picture-in-picture is present; measurement stopped')
    device.shell('am', 'force-stop', PACKAGE)
    start_temperature = parse_temperature(device.shell('dumpsys', 'battery'))
    collector = None
    log_file = None
    drags = []
    launch_ns = end_ns = None
    try:
        # Starting at this device-wall-clock second retains launch events even if logcat attaches late.
        # elapsedRealtimeNanos below discards older samples without clearing anyone else's log buffer.
        log_start = device.shell('date', '+%m-%d %H:%M:%S.000').strip()
        if not re.fullmatch(r'\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.000', log_start):
            raise RuntimeError('Device log collection clock unavailable')
        log_file = (args.output / 'events.log').open('w')
        collector = subprocess.Popen(device.command + ['logcat', '-v', 'brief', '-T', log_start, '-s', 'LoupeRawEdit:I', '*:S'],
                                     stdout=log_file, stderr=subprocess.DEVNULL)
        device.unlocked()
        launch_ns = device.clock()
        command = ['am', 'start', '-n', PACKAGE + '/jp.nano.rawgallery.RawEditActivity']
        command += ['-d', uri, '--grant-read-uri-permission'] if uri else ['--ez', 'rawEditSynthetic', 'true']
        device.shell(*command)
        until = time.monotonic() + 90
        while True:
            if collector.poll() is not None:
                raise RuntimeError('Anonymous event collector stopped unexpectedly')
            xml = device.xml()
            if any(n.attrib.get('package') == PACKAGE and n.attrib.get('text') == '保存' and n.attrib.get('enabled') == 'true' for n in xml.iter('node')):
                break
            if time.monotonic() >= until:
                raise RuntimeError('Experimental preview did not become ready within 90 seconds')
            time.sleep(.2)
        texture = next((n for n in xml.iter('node') if n.attrib.get('package') == PACKAGE and n.attrib.get('text') == '質感'), None)
        if texture is None:
            raise RuntimeError('Experimental texture tab unavailable')
        b = bounds(texture)
        device.shell('input', 'tap', str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2))
        plane = next((n for n in device.xml().iter('node') if n.attrib.get('package') == PACKAGE and 'の面。' in n.attrib.get('content-desc', '')), None)
        if plane is None:
            raise RuntimeError('Experimental edit plane unavailable')
        b = bounds(plane)
        if args.wait_prepared:
            until = time.monotonic() + 120
            while not any(e['name'] == 'full.prepare.complete' for e in parse_events((args.output / 'events.log').read_text(), launch_ns)['events']):
                device.unlocked()
                if collector.poll() is not None:
                    raise RuntimeError('Anonymous event collector stopped unexpectedly')
                if time.monotonic() >= until:
                    raise RuntimeError('Original preparation did not complete within 120 seconds')
                time.sleep(.2)
        x1, x2 = int(b[0] + .2 * (b[2] - b[0])), int(b[0] + .8 * (b[2] - b[0]))
        y1, y2 = int(b[1] + .7 * (b[3] - b[1])), int(b[1] + .2 * (b[3] - b[1]))
        for index in range(6):
            device.unlocked()
            focus = parse_focus(device.shell('dumpsys', 'window'))
            if focus == 'unavailable':
                raise RuntimeError('Current focus diagnostic unavailable; measurement stopped')
            if focus != 'experimental-editor':
                raise RuntimeError('Experimental editor lost focus; measurement stopped')
            start = device.clock()
            points = (x1, y1, x2, y2) if index % 2 == 0 else (x2, y2, x1, y1)
            device.shell('input', 'swipe', *(str(v) for v in points), '1800')
            drags.append({'start_ns': start, 'end_ns': device.clock()})
            time.sleep(.6)
        time.sleep(.8)
        device.unlocked()
        end_ns = device.clock()
        if collector.poll() is not None:
            raise RuntimeError('Anonymous event collector stopped unexpectedly')
    finally:
        if collector is not None:
            collector.terminate()
            try:
                collector.wait(timeout=5)
            except subprocess.TimeoutExpired:
                collector.kill()
                collector.wait(timeout=5)
        if log_file is not None:
            log_file.close()
    # Memory collection intentionally begins after the input/frame measurement boundary.
    result = summarize((args.output / 'events.log').read_text(), mode, 'selected RAW' if uri else 'synthetic', launch_ns, end_ns, drags)
    result['temperature_c'] = {'start': start_temperature, 'end': parse_temperature(device.shell('dumpsys', 'battery'))}
    result['kernel_memory_kb'] = {'rss_kb': None, 'hwm_kb': None}
    try:
        pid = device.shell('pidof', PACKAGE).strip()
        if re.fullmatch(r'\d+', pid):
            result['kernel_memory_kb'] = parse_kernel_memory(device.shell('run-as', PACKAGE, 'cat', '/proc/' + pid + '/status'))
    except RuntimeError:
        pass
    try:
        result['memory_pss_kb'] = parse_meminfo(device.shell('dumpsys', 'meminfo', PACKAGE))
    except RuntimeError:
        result['memory_pss_kb'] = parse_meminfo('')
    (args.output / 'summary.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))
    return 0 if result['status'] == 'PASS' else 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except RuntimeError as error:
        # RuntimeError messages are fixed strings created in this module, never subprocess text.
        print('FAIL: ' + str(error), file=sys.stderr)
        sys.exit(1)
    except (OSError, UnicodeError):
        # System exception text/argv may contain a URI or path.
        print('FAIL: local output or anonymous collector unavailable', file=sys.stderr)
        sys.exit(1)
