#!/usr/bin/env python3
"""Apply verified V3 configuration to an unchanged V2 offline DB, transactionally.

Stories stay in the original database inode. Default is read-only verification.
The baseline is produced from a consistent private backup before staging.
"""
import argparse
import hashlib
import json
import os
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = ROOT / 'apps/server/src/main/resources/db/V3__capabilities.sql'


def fingerprint(db, table):
    rows = db.execute('SELECT * FROM "' + table.replace('"', '""') + '"').fetchall()
    return hashlib.sha256(repr(sorted(rows, key=repr)).encode()).hexdigest()


def apply(database, staged, baseline, backup, commit=False):
    database, staged, baseline, backup = map(lambda p: Path(p).resolve(), (database, staged, baseline, backup))
    if len({database, staged, backup}) != 3 or not all(p.is_file() for p in (database, staged, baseline, backup)):
        raise ValueError('Distinct existing source, staged, baseline and backup files required')
    expected = json.loads(baseline.read_text())
    with sqlite3.connect(backup.as_uri() + '?mode=ro', uri=True) as saved:
        if any(fingerprint(saved, t) != digest for t, digest in expected.items()):
            raise ValueError('Backup does not match the baseline')
    key_path = staged.parent / '.mcp-key'
    key = key_path.read_bytes()
    if not key or len(key) > 100:
        raise ValueError('Invalid staged MCP encryption key')
    checksum = hashlib.sha256(SCHEMA.read_bytes()).hexdigest()
    with sqlite3.connect(staged.as_uri() + '?mode=ro', uri=True) as source:
        if source.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
            raise ValueError('Staged database integrity check failed')
        if source.execute('SELECT checksum FROM schema_version WHERE version=3').fetchone() != (checksum,):
            raise ValueError('Staged V3 schema checksum mismatch')
        skills = source.execute('SELECT * FROM companion_skill').fetchall()
        servers = source.execute('SELECT * FROM companion_mcp').fetchall()
        settings = source.execute('SELECT * FROM companion_setting').fetchall()
    target_key = database.parent / '.mcp-key'
    if target_key.exists() and target_key.read_bytes() != key:
        raise ValueError('Existing MCP key differs; preserve it and restage')
    created_key = False
    target = sqlite3.connect(database if commit else database.as_uri() + '?mode=ro', uri=not commit, timeout=10)
    try:
        target.execute('BEGIN IMMEDIATE' if commit else 'BEGIN')
        if target.execute('SELECT COUNT(*) FROM schema_version WHERE version>=3').fetchone()[0]:
            raise ValueError('Target is already V3 or later; this tool never overwrites existing configuration')
        tables = {r[0] for r in target.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")}
        if tables != set(expected):
            raise ValueError('Target schema changed after backup; restage')
        if any(fingerprint(target, t) != digest for t, digest in expected.items()):
            raise ValueError('Target changed after backup; create a fresh backup and restage')
        result = {'mode': 'applied' if commit else 'verified_dry_run', 'skills': len(skills), 'servers': len(servers), 'baselineTablesChecked': len(expected), 'backup': str(backup)}
        if not commit:
            target.rollback()
            return result
        if not target_key.exists():
            with os.fdopen(os.open(target_key, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as output:
                output.write(key)
                output.flush()
                os.fsync(output.fileno())
            created_key = True
        for statement in SCHEMA.read_text().split('\n--statement--\n'):
            target.execute(statement)
        target.executemany('INSERT INTO companion_skill VALUES(?,?,?)', skills)
        target.executemany('INSERT INTO companion_mcp VALUES(?,?,?)', servers)
        # Preserve existing choices. A previously absent settings row receives
        # the migrated schedule with networking still disabled by the runtime.
        target.executemany('INSERT OR IGNORE INTO companion_setting VALUES(?,?)', settings)
        target.execute('INSERT INTO schema_version VALUES(3,?)', (checksum,))
        for table, digest in expected.items():
            if table not in ('schema_version', 'companion_setting') and fingerprint(target, table) != digest:
                raise ValueError('Unexpected change to original table: ' + table)
        if target.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
            raise ValueError('Final integrity check failed')
        target.commit()
        return result
    except BaseException:
        target.rollback()
        if created_key:
            target_key.unlink()
        raise
    finally:
        target.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('database', 'staged', 'baseline', 'backup'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    print(json.dumps(apply(args.database, args.staged, args.baseline, args.backup, args.apply), ensure_ascii=False, indent=2))
