#!/usr/bin/env python3
"""Publish a native Loupe recovery index for verified Amazon Photos migration records."""

import argparse
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
import hashlib
import json
import os
import re
import sys
import time
import unicodedata

MIGRATION = 'loupe/migration/amazon-photos/v1/'
ORIGINALS = 'loupe/originals/'
PARTS = 'loupe/catalog/parts/'
INDEX = 'loupe/catalog/v2.json'
LEGACY = 'loupe/catalog/v1.json'
ROOT_BYTES = 512 * 1024
PART_BYTES = 256 * 1024
PART_ROWS = 256
MAX_PARTS = 512
MAX_ROWS = PART_ROWS * MAX_PARTS
MAX_ORIGINAL = 50_000_000_000_000
SUPPORTED = frozenset('''3fr ari arq arw bay bmq cap cr2 cr3 crw cs1 dc2 dcr dng drf eip erf fff gpr ia iiq
k25 kc2 kdc mdc mef mos mrw nef nrw obm orf ori pef ptx pxn qtk raf raw rdc rw1 rw2 rwl rwz sr2 srf srw
sti tif tiff x3f jpg jpeg mp4 m4v mov 3gp 3gpp mkv webm'''.split())


class Invalid(ValueError):
    """A failed precondition. Messages contain no object names or credentials."""


def require(condition, message):
    if not condition:
        raise Invalid(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encode(value):
    return json.dumps(value, ensure_ascii=True, separators=(',', ':'), allow_nan=False).encode()


def decode(data):
    def unique(pairs):
        fields = dict(pairs)
        require(len(fields) == len(pairs), 'Repeated JSON field')
        return fields

    def invalid_constant(_):
        raise Invalid('Invalid JSON number')

    try:
        return json.loads(data, object_pairs_hook=unique, parse_constant=invalid_constant)
    except (ValueError, UnicodeError, RecursionError) as error:
        raise Invalid('Invalid catalog JSON') from error


def envelope(document):
    payload = encode(document)
    return encode({'payload': payload.decode(), 'sha256': digest(payload)})


def document(data, bucket, schema):
    obj = decode(data)
    require(isinstance(obj, dict) and isinstance(obj.get('payload'), str), 'Invalid catalog envelope')
    payload = obj['payload'].encode()
    require(digest(payload) == obj.get('sha256'), 'Catalog checksum mismatch')
    doc = decode(payload)
    require(isinstance(doc, dict) and integer(doc.get('schema'), schema, schema) and doc.get('bucket') == bucket
            and integer(doc.get('created'), 1), 'Unexpected catalog schema, bucket or declared time')
    return doc


def matches(value, pattern):
    return isinstance(value, str) and re.fullmatch(pattern, value) is not None


def valid_version(value):
    return value != 'null' and matches(value, r'[A-Za-z0-9_-]{1,512}')


def integer(value, lower, upper=2**63 - 1):
    return type(value) is int and lower <= value <= upper


def native(row, bucket):
    require(isinstance(row, dict), 'Invalid catalog row')
    key, sha, name, version = (row.get(x) for x in ('key', 'sha', 'name', 'version'))
    require(matches(key, r'[0-9a-f]{32}') and matches(sha, r'[0-9a-f]{64}')
            and valid_version(version) and integer(row.get('size'), 1, MAX_ORIGINAL)
            and integer(row.get('taken', 0), 0) and integer(row.get('verifiedAt', 0), 0)
            and row.get('bucket') == bucket and row.get('provider') == 'idrive-e2'
            and row.get('state') == 'verified'
            and isinstance(name, str) and len(name.encode('utf-16-le', errors='surrogatepass')) <= 510
            and name.rfind('.') > 0 and name.rsplit('.', 1)[1].lower() in SUPPORTED
            and not any(c in '/\\' or ord(c) < 32 or ord(c) == 127
                        or unicodedata.category(c) == 'Cs' for c in name), 'Invalid catalog row')
    return row


@dataclass(frozen=True)
class CatalogObject:
    data: bytes
    version: str
    etag: str


class Storage:
    def __init__(self, client, bucket):
        self.client = client
        self.bucket = bucket

    def read(self, key, limit, version=None):
        options = {'VersionId': version} if version else {}
        try:
            response = self.client.get_object(Bucket=self.bucket, Key=key, **options)
        except Exception as error:
            code = getattr(error, 'response', {}).get('Error', {}).get('Code')
            if code in ('404', 'NoSuchKey', 'NoSuchVersion', 'NotFound'):
                return None
            raise
        with response['Body'] as stream:
            length, actual = response['ContentLength'], response.get('VersionId')
            require(integer(length, 1, limit) and valid_version(actual)
                    and (version is None or actual == version), 'Invalid object length or version')
            data = stream.read(length + 1)
            require(len(data) == length, 'Incomplete catalog download')
            return CatalogObject(data, actual, response.get('ETag', ''))

    def root(self, key):
        current = self.read(key, ROOT_BYTES)
        if current is None:
            pages = self.client.get_paginator('list_object_versions')
            for page in pages.paginate(Bucket=self.bucket, Prefix=key):
                require(not any(obj['Key'] == key for obj in page.get('Versions', [])),
                        'Current catalog missing but older versions exist')
        return current

    def verify_original(self, row):
        key = ORIGINALS + row['key'] + '/' + row['sha']
        version = row['version']
        response = self.client.head_object(Bucket=self.bucket, Key=key, VersionId=version)
        require(response.get('VersionId') == version and response.get('ContentLength') == row['size'],
                'Migrated original does not match its declared version and size')

    def write(self, key, data, current=None):
        require(key == INDEX or key == PARTS + digest(data) + '.json', 'Unexpected publication target')
        condition = {'IfNoneMatch': '*'}
        if current is not None:
            require(matches(current.etag, r'"[A-Za-z0-9_-]{1,128}"'), 'Invalid catalog ETag')
            condition = {'IfMatch': current.etag}
        response = self.client.put_object(Bucket=self.bucket, Key=key, Body=data,
                                          ContentType='application/json', **condition)
        version = response.get('VersionId')
        require(valid_version(version), 'Publication returned no object version')
        verified = self.read(key, ROOT_BYTES, version)
        require(verified is not None and verified.data == data, 'Published bytes did not verify')
        return verified


def migration_row(storage, key):
    saved = storage.read(key, 16 * 1024)
    require(saved is not None, 'Migration record disappeared while reading')
    data = decode(saved.data)
    require(isinstance(data, dict) and integer(data.get('format'), 1, 1) and isinstance(data.get('photos'), list)
            and len(data['photos']) == 1 and isinstance(data['photos'][0], dict),
            'Unexpected Amazon migration record format')
    item = data['photos'][0]
    sha, short = item.get('sha256'), item.get('id')
    require(matches(sha, r'[0-9a-f]{64}') and short == sha[:32] and key == MIGRATION + sha + '.json'
            and item.get('object') == ORIGINALS + short + '/' + sha, 'Invalid original reference')
    row = native({'key': short, 'sha': sha, 'version': item.get('version'), 'size': item.get('size'),
                  'name': item.get('name'), 'taken': item.get('taken', 0), 'verifiedAt': 0,
                  'bucket': storage.bucket, 'provider': 'idrive-e2', 'state': 'verified'}, storage.bucket)
    # The migration writer publishes this record only after comparing the original bytes.
    # Reconfirm its immutable object version and size without downloading the original again.
    storage.verify_original(row)
    return row


def migration_pages(storage):
    paginator = storage.client.get_paginator('list_objects_v2')
    for page in paginator.paginate(Bucket=storage.bucket, Prefix=MIGRATION):
        records = [(obj['Key'], obj.get('ETag'), obj.get('Size')) for obj in page.get('Contents', [])]
        require(all(matches(key, re.escape(MIGRATION) + r'[0-9a-f]{64}\.json') for key, _, _ in records),
                'Unexpected object in the Amazon migration namespace')
        yield records


def migration_rows(storage, workers):
    rows, listing = [], []
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for page in migration_pages(storage):
            require(len(rows) + len(page) <= MAX_ROWS, 'Too many migration records for the recovery index')
            listing.extend(page)
            rows.extend(pool.map(lambda item: migration_row(storage, item[0]), page))
            if rows and len(rows) % 5000 == 0:
                print(f'Verified migration records: {len(rows)}', file=sys.stderr, flush=True)
    require(rows, 'No Amazon migration records found')
    return rows, listing


def existing_rows(storage, current, legacy):
    if current is not None:
        index = document(current.data, storage.bucket, 2)
        refs = index.get('parts')
        require(isinstance(refs, list) and 0 < len(refs) <= MAX_PARTS
                and integer(index.get('count'), 1, MAX_ROWS), 'Invalid catalog index bounds')
        total, seen = 0, set()
        for ref in refs:
            require(isinstance(ref, dict) and matches(ref.get('sha256'), r'[0-9a-f]{64}')
                    and valid_version(ref.get('version')) and integer(ref.get('count'), 1, PART_ROWS),
                    'Invalid catalog part reference')
            sha = ref['sha256']
            require(sha not in seen, 'Repeated catalog part')
            seen.add(sha)
            part = storage.read(PARTS + sha + '.json', PART_BYTES, ref['version'])
            require(part is not None and digest(part.data) == sha, 'Existing catalog part did not verify')
            rows = decode(part.data)
            require(isinstance(rows, list) and len(rows) == ref['count'], 'Existing catalog part count mismatch')
            total += len(rows)
            yield from rows
        require(total == index['count'], 'Existing catalog total mismatch')
    if legacy is not None:
        doc = document(legacy.data, storage.bucket, 1)
        rows = doc.get('photos')
        require(isinstance(rows, list) and integer(doc.get('count'), 1, 2000)
                and len(rows) == doc['count'], 'Invalid legacy catalog row count')
        yield from rows


def merge(storage, existing, additions):
    rows = {}
    for group in (existing, additions):
        for raw in group:
            row = native(raw, storage.bucket)
            key = row['key']
            previous = rows.get(key)
            if previous is None:
                rows[key] = row
                continue
            require(all(previous[field] == row[field] for field in ('sha', 'size', 'name')),
                    'Migration row conflicts with existing recovery catalog')
            if previous['version'] != row['version']:
                storage.verify_original(previous)
    return rows


def split(rows):
    parts, entries, length = [], [], 2  # Array brackets are emitted later.
    for row in rows:
        encoded = encode(row)
        require(len(encoded) + 2 <= PART_BYTES, 'One recovery row exceeds the part size')
        if len(entries) == PART_ROWS or length + 1 + len(encoded) > PART_BYTES:
            parts.append((b'[' + b','.join(entries) + b']', len(entries)))
            entries, length = [], 2
        length += len(encoded) + bool(entries)
        entries.append(encoded)
    if entries:
        parts.append((b'[' + b','.join(entries) + b']', len(entries)))
    require(0 < len(parts) <= MAX_PARTS, 'The recovery index needs too many parts')
    return parts


def publish(storage, current, legacy, rows, parts):
    refs = []
    for body, count in parts:
        sha = digest(body)
        key = PARTS + sha + '.json'
        existing = storage.read(key, PART_BYTES)
        if existing is None:
            verified = storage.write(key, body)
        else:
            verified = storage.read(key, PART_BYTES, existing.version)
        require(verified is not None and verified.data == body, 'Existing part does not match its content key')
        refs.append({'sha256': sha, 'version': verified.version, 'count': count})
    require(storage.root(LEGACY) == legacy, 'Legacy catalog changed during preparation')
    require(storage.root(INDEX) == current, 'Current catalog changed during preparation')
    if current is not None and document(current.data, storage.bucket, 2)['parts'] == refs:
        return current.version
    root = envelope({'schema': 2, 'bucket': storage.bucket, 'created': time.time_ns() // 1_000_000,
                     'count': len(rows), 'parts': refs})
    require(len(root) <= ROOT_BYTES, 'Recovery index is too large')
    verified = storage.write(INDEX, root, current)
    require(storage.root(INDEX) == verified, 'A different generation is current after publication')
    return verified.version


def run(storage, workers, expect_count=None, write=False):
    require(storage.client.get_bucket_versioning(Bucket=storage.bucket).get('Status') == 'Enabled',
            'Bucket versioning is required')
    current, legacy = storage.root(INDEX), storage.root(LEGACY)
    records, listing = migration_rows(storage, workers)
    require(expect_count is None or len(records) == expect_count, 'Migration record count differs from expected')
    require(not write or expect_count is not None, 'An explicit migration record count is required to publish')
    rows = merge(storage, existing_rows(storage, current, legacy), records)
    require(len(rows) <= MAX_ROWS, 'Too many rows for the recovery index')
    parts = split(rows.values())
    print(f'Migration records: {len(records)}; merged recovery rows: {len(rows)}; parts: {len(parts)}', flush=True)
    if write:
        latest = [item for page in migration_pages(storage) for item in page]
        require(listing == latest, 'Migration records changed during preparation')
        publish(storage, current, legacy, rows, parts)
        print('Verified the published recovery index and all referenced parts.', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bucket', required=True, choices=('loupe-test', 'loupe-data'))
    parser.add_argument('--workers', type=int, default=16, choices=range(1, 33), metavar='1-32')
    parser.add_argument('--expect-count', type=int, help='Expected number of individual migration records')
    parser.add_argument('--publish', action='store_true', help='Publish the resulting recovery index')
    args = parser.parse_args()
    try:
        import boto3
        from botocore.config import Config

        endpoint = os.getenv('E2_ENDPOINT', '').removeprefix('https://').rstrip('/')
        require(endpoint == 's3.ap-northeast-1.idrivee2.com' and os.getenv('E2_REGION') == 'ap-northeast-1',
                'Expected the configured Tokyo IDrive e2 endpoint')
        client = boto3.client('s3', endpoint_url='https://' + endpoint, region_name='ap-northeast-1',
                              aws_access_key_id=os.environ['E2_ACCESS_KEY_ID'],
                              aws_secret_access_key=os.environ['E2_SECRET_ACCESS_KEY'],
                              config=Config(s3={'addressing_style': 'path'}, max_pool_connections=args.workers,
                                            request_checksum_calculation='when_required',
                                            response_checksum_validation='when_required',
                                            connect_timeout=10, read_timeout=30,
                                            retries={'mode': 'standard', 'max_attempts': 3}))
        run(Storage(client, args.bucket), args.workers, args.expect_count, args.publish)
    except Invalid as error:
        print(str(error), file=sys.stderr)
        return 2
    except Exception as error:
        code = getattr(error, 'response', {}).get('Error', {}).get('Code', '')
        reason = code if matches(code, r'[A-Za-z0-9_-]{1,80}') else type(error).__name__
        print('Migration check failed: ' + reason, file=sys.stderr)
        return 2
    return 0


if __name__ == '__main__':
    sys.exit(main())
