#!/usr/bin/env python3
"""Restore legacy interests into an existing offline database, with an SQLite backup.

Dry-run by default. This does not enable network access or modify story tables.
"""
import argparse
import hashlib
import json
import sqlite3
import uuid
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
V1 = ROOT / 'apps/server/src/main/resources/db/V1__sqlite.sql'
V2 = ROOT / 'apps/server/src/main/resources/db/V2__companion.sql'


def restore(database, review, user_ids, *, apply=False, backup=None):
    database, review = Path(database).resolve(), Path(review).resolve()
    if not database.is_file() or not review.is_file():
        raise ValueError('An existing offline database and legacy review file are required')
    if review.stat().st_size > 2 * 1024 * 1024:
        raise ValueError('Legacy review exceeds 2 MiB')
    data = json.loads(review.read_text())
    records = data.get('proactive_interest', data.get('companion_interests', []))
    if not isinstance(records, list):
        raise ValueError('Review must contain an interest list')
    selected = []
    for row in records:
        if int(row.get('user_id', -1)) not in user_ids:
            continue
        topic = str(row.get('topic', '')).strip()
        if not topic or len(topic) > 128:
            raise ValueError('Invalid legacy interest topic')
        sentiment = row.get('sentiment', 'like')
        if sentiment not in ('like', 'dislike'):
            raise ValueError('Invalid legacy interest sentiment')
        selected.append((topic, sentiment, bool(row.get('enabled', True))))
    if len(selected) > 200:
        raise ValueError('At most 200 interests can be restored')
    # rw never creates a database accidentally; dry-run uses a read-only connection.
    source = sqlite3.connect(database.as_uri() + ('?mode=rw' if apply else '?mode=ro'), uri=True, timeout=10)
    try:
        versions = dict(source.execute('SELECT version,checksum FROM schema_version'))
        if versions.get(1) != hashlib.sha256(V1.read_bytes()).hexdigest():
            raise ValueError('Offline V1 schema checksum does not match; database was not changed')
        checksum = hashlib.sha256(V2.read_bytes()).hexdigest()
        if 2 in versions and versions[2] != checksum:
            raise ValueError('Companion V2 checksum does not match; database was not changed')
        if not apply:
            return {'selected': len(selected), 'applied': False}
        if backup is None:
            raise ValueError('An explicit private backup path is required for --apply')
        backup = Path(backup).resolve()
        if backup == database or backup.exists():
            raise ValueError('Choose a new backup path')
        backup.parent.mkdir(parents=True, exist_ok=True)
        backup.touch(mode=0o600, exist_ok=False)
        with closing(sqlite3.connect(backup)) as snapshot:
            source.backup(snapshot)
            if snapshot.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
                raise ValueError('Backup verification failed; database was not changed')
        source.execute('BEGIN IMMEDIATE')
        try:
            # Recheck after taking the writer lock in case the app upgraded meanwhile.
            current = dict(source.execute('SELECT version,checksum FROM schema_version'))
            if 2 not in current:
                for statement in V2.read_text().split('--statement--'):
                    source.execute(statement.strip().rstrip(';'))
                source.execute('INSERT INTO schema_version VALUES(2,?)', (checksum,))
            elif current[2] != checksum:
                raise ValueError('Companion schema changed concurrently')
            added = 0
            for topic, sentiment, enabled in selected:
                if source.execute('SELECT COUNT(*) FROM companion_interest WHERE user_id=1 AND topic=?', (topic,)).fetchone()[0]:
                    continue
                count = source.execute('SELECT COUNT(*) FROM companion_interest WHERE user_id=1').fetchone()[0]
                if count >= 200:
                    raise ValueError('Restoration would exceed 200 interests; no changes applied')
                added += source.execute('INSERT INTO companion_interest(id,user_id,topic,sentiment,source,enabled,created_at) VALUES(?,1,?,?,?,?,?) ON CONFLICT(user_id,topic) DO NOTHING', (str(uuid.uuid4()), topic, sentiment, 'legacy', enabled, datetime.now(timezone.utc).isoformat())).rowcount
            if source.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
                raise ValueError('Database integrity check failed')
            source.commit()
        except Exception:
            source.rollback()
            raise
        return {'selected': len(selected), 'added': added, 'applied': True, 'backup': str(backup)}
    finally:
        source.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', type=Path, required=True)
    parser.add_argument('--review', type=Path, required=True)
    parser.add_argument('--user-id', type=int, nargs='+', required=True)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--backup', type=Path)
    args = parser.parse_args()
    print(json.dumps(restore(args.database, args.review, args.user_id, apply=args.apply, backup=args.backup), ensure_ascii=False))
