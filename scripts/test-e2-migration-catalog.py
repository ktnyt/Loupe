#!/usr/bin/env python3
"""Exercise the migration writer against a fake versioned object store."""

import contextlib
import importlib.util
import io
from pathlib import Path
import re
import sys
import unittest

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location('migration', HERE / 'e2-migration-catalog.py')
migration = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = migration
SPEC.loader.exec_module(migration)


class S3Error(RuntimeError):
    def __init__(self, code):
        super().__init__(code)
        self.response = {'Error': {'Code': code}}


class FakeS3:
    def __init__(self):
        self.objects = {}
        self.puts = []
        self.override_index = False
        self.versioning = 'Enabled'

    def insert(self, key, data):
        versions = self.objects.setdefault(key, {})
        version = 'v' + str(len(versions) + 1)
        versions[version] = data
        return version

    def latest(self, key):
        versions = self.objects.get(key, {})
        if not versions:
            raise S3Error('NoSuchKey')
        return next(reversed(versions))

    def get_bucket_versioning(self, **options):
        return {'Status': self.versioning}

    def get_paginator(self, operation):
        client = self

        class Paginator:
            def paginate(self, Bucket, Prefix):
                keys = sorted(key for key in client.objects if key.startswith(Prefix))
                if operation == 'list_objects_v2':
                    return [{'Contents': [{'Key': key, 'Size': len(client.objects[key][client.latest(key)]),
                                           'ETag': client.etag(key)} for key in keys]}]
                if operation == 'list_object_versions':
                    return [{'Versions': [{'Key': key} for key in keys]}]
                raise AssertionError(operation)

        return Paginator()

    def head_object(self, Bucket, Key, VersionId):
        try:
            data = self.objects[Key][VersionId]
        except KeyError as error:
            raise S3Error('NoSuchVersion') from error
        return {'ContentLength': len(data), 'VersionId': VersionId}

    def etag(self, key, version=None):
        return '"' + migration.digest(self.objects[key][version or self.latest(key)]) + '"'

    def get_object(self, Bucket, Key, VersionId=None, IfMatch=None):
        version = VersionId or self.latest(Key)
        result = self.head_object(Bucket, Key, version)
        data = self.objects[Key][version]
        tag = self.etag(Key, version)
        if IfMatch is not None and IfMatch != tag:
            raise S3Error('PreconditionFailed')
        return {**result, 'ETag': tag, 'Body': io.BytesIO(data)}

    def put_object(self, Bucket, Key, Body, ContentType, **condition):
        self.puts.append((Key, condition))
        if Key == migration.INDEX and self.override_index:
            self.insert(Key, b'concurrent publisher')
        if 'IfNoneMatch' in condition:
            if Key in self.objects:
                raise S3Error('PreconditionFailed')
        else:
            current = self.get_object(Bucket, Key)['ETag']
            if current != condition['IfMatch']:
                raise S3Error('PreconditionFailed')
        return {'VersionId': self.insert(Key, Body)}


class MigrationTests(unittest.TestCase):
    def setUp(self):
        self.client = FakeS3()
        self.storage = migration.Storage(self.client, 'loupe-test')
        self.addition = self.seed(b'fixture-one', 'はじめ.jpg')

    def seed(self, original, name):
        sha = migration.digest(original)
        object_key = f'{migration.ORIGINALS}{sha[:32]}/{sha}'
        version = self.client.insert(object_key, original)
        key = migration.MIGRATION + sha + '.json'
        entry = {'format': 1, 'id': sha[:32], 'object': object_key, 'version': version, 'sha256': sha,
                 'size': len(original), 'name': name, 'taken': 0}
        self.client.insert(key, migration.encode({'format': 1, 'photos': [entry]}))
        return key

    def publish(self, count=1):
        with contextlib.redirect_stdout(io.StringIO()):
            migration.run(self.storage, 2, count, write=True)

    def rows(self):
        root = self.storage.root(migration.INDEX)
        return list(migration.existing_rows(self.storage, root, None))

    def test_matching_native_formats(self):
        directory = HERE.parent / 'app/src/main/java/jp/nano/rawgallery'
        raw = (directory / 'RawFormats.java').read_text()
        video = (directory / 'VideoFormats.java').read_text()
        raw_ext = re.search(r'RAW_EXTENSIONS\s*=\s*"([^"]+)"', raw)[1]
        video_ext = re.search(r'EXTENSIONS\s*=\s*"([^"]+)"', video)[1]
        self.assertEqual(migration.SUPPORTED, frozenset((raw_ext + '|jpg|jpeg|' + video_ext).split('|')))

    def test_read_only_then_publish_and_repeat(self):
        with contextlib.redirect_stdout(io.StringIO()):
            migration.run(self.storage, 2)
        self.assertEqual(self.client.puts, [])
        self.publish()
        [row] = self.rows()
        self.assertEqual(row['name'], 'はじめ.jpg')
        self.assertEqual(row['version'], 'v1')
        self.assertEqual(row['taken'], 0)
        part, root = self.client.puts
        self.assertTrue(part[0].startswith(migration.PARTS))
        self.assertEqual(root, (migration.INDEX, {'IfNoneMatch': '*'}))
        self.publish()
        self.assertEqual(len(self.client.puts), 2)

    def test_preserves_existing_rows_and_writes_with_etag(self):
        self.publish()
        self.seed(b'fixture-two', 'twice.dng')
        self.publish(count=2)
        self.assertEqual({row['name'] for row in self.rows()}, {'はじめ.jpg', 'twice.dng'})
        self.assertIn('IfMatch', self.client.puts[-1][1])

    def test_cannot_replace_concurrent_index(self):
        self.client.override_index = True
        with self.assertRaises(S3Error):
            self.publish()
        self.assertEqual(self.storage.root(migration.INDEX).data, b'concurrent publisher')

    def test_invalid_reference_and_unexpected_count_never_write(self):
        with self.assertRaisesRegex(migration.Invalid, 'count differs'):
            self.publish(count=2)
        row = migration.migration_row(self.storage, next(iter(migration.migration_pages(self.storage)))[0])
        original = migration.ORIGINALS + row['key'] + '/' + row['sha']
        self.client.objects[original]['v1'] += b'extra'
        with self.assertRaisesRegex(migration.Invalid, 'declared version and size'):
            self.publish()
        self.assertFalse(self.client.puts)

    def test_refuses_conflict_with_legacy(self):
        row = migration.migration_row(self.storage, next(iter(migration.migration_pages(self.storage)))[0])
        row['name'] = 'different.jpg'
        legacy = migration.envelope({'schema': 1, 'bucket': self.storage.bucket, 'created': 1,
                                     'count': 1, 'photos': [row]})
        self.client.insert(migration.LEGACY, legacy)
        with self.assertRaisesRegex(migration.Invalid, 'conflicts'):
            self.publish()
        self.assertFalse(self.client.puts)

    def test_existing_different_version_must_hash_to_expected_bytes(self):
        self.publish()
        [row] = self.rows()
        key = migration.ORIGINALS + row['key'] + '/' + row['sha']
        self.client.objects[key]['v1'] = b'X' * row['size']
        valid = self.client.insert(key, b'fixture-one')
        document = migration.decode(self.storage.read(self.addition, 16384).data)
        document['photos'][0]['version'] = valid
        self.client.insert(self.addition, migration.encode(document))
        with self.assertRaisesRegex(migration.Invalid, 'does not match its SHA-256'):
            self.publish()
        self.assertEqual(len(self.client.puts), 2)

    def test_reports_missing_pinned_part_after_index_write(self):
        put = self.client.put_object

        def losing_part(**options):
            result = put(**options)
            if options['Key'] == migration.INDEX:
                part = next(key for key in self.client.objects if key.startswith(migration.PARTS))
                del self.client.objects[part]
            return result

        self.client.put_object = losing_part
        with self.assertRaisesRegex(migration.Invalid, 'lost a pinned part'):
            self.publish()

    def test_rejects_native_invalid_legacy_generations(self):
        listed = next(iter(migration.migration_pages(self.storage)))[0]
        row = migration.migration_row(self.storage, listed)
        invalid = (
            [row, row],
            [dict(row, size=migration.LEGACY_ORIGINAL + 1)],
            [dict(row, ignored=[[[[[[[[]]]]]]]])],
        )
        for entries in invalid:
            with self.subTest(count=len(entries)):
                root = migration.envelope({'schema': 1, 'bucket': self.storage.bucket, 'created': 1,
                                           'count': len(entries), 'photos': entries})
                self.client.insert(migration.LEGACY, root)
                with self.assertRaises(migration.Invalid):
                    self.publish()
        self.assertEqual(self.client.puts, [])

    def test_get_uses_etag_from_migration_listing(self):
        listed = next(iter(migration.migration_pages(self.storage)))[0]
        document = migration.decode(self.storage.read(self.addition, 16384).data)
        document['photos'][0]['name'] = 'replaced.jpg'
        self.client.insert(self.addition, migration.encode(document))
        with self.assertRaises(S3Error):
            migration.migration_row(self.storage, listed)
        self.assertEqual(self.client.puts, [])

    def test_getting_new_records_while_running_can_be_retried(self):
        verify = self.storage.verify_original
        appended = False

        def append_after_first_verification(row, **kwargs):
            nonlocal appended
            verify(row, **kwargs)
            if not appended:
                self.seed(b'added after list', 'late.jpg')
                appended = True

        self.storage.verify_original = append_after_first_verification
        self.publish()
        self.assertEqual(len(self.rows()), 1)
        self.publish(count=2)
        self.assertEqual(len(self.rows()), 2)

    def test_split_respects_rows_and_bytes(self):
        parts = migration.split([{'name': 'a.jpeg'}] * (migration.PART_ROWS + 1))
        self.assertEqual([count for _, count in parts], [migration.PART_ROWS, 1])
        parts = migration.split([{'name': 'a' * (migration.PART_BYTES // 2)}] * 2)
        self.assertEqual([count for _, count in parts], [1, 1])
        self.assertTrue(all(len(body) <= migration.PART_BYTES for body, _ in parts))


if __name__ == '__main__':
    unittest.main()
