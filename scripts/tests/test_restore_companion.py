import importlib.util
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from contextlib import closing

spec=importlib.util.spec_from_file_location('restore_companion',Path(__file__).resolve().parents[1]/'release/restore-companion.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class RestoreTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='story-restore-test-');self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.db=self.root/'stories.sqlite';self.review=self.root/'review.json';self.backup=self.root/'backup.sqlite'
        with closing(sqlite3.connect(self.db)) as db:
            for statement in module.V1.read_text().split('--statement--'):
                db.execute(statement.strip().rstrip(';'))
            db.execute('CREATE TABLE schema_version(version INTEGER PRIMARY KEY,checksum TEXT NOT NULL)')
            db.execute('INSERT INTO schema_version VALUES(1,?)',(module.hashlib.sha256(module.V1.read_bytes()).hexdigest(),))
            db.execute("INSERT INTO conversation(id,user_id,role_id,title) VALUES('keep',1,1,'原有故事')");db.commit()
        self.review.write_text(json.dumps({'proactive_interest':[{'user_id':0,'topic':'摄影','enabled':False},{'user_id':1,'topic':'开源模型'},{'user_id':99,'topic':'另一个账号'}]}))
    def test_read_only_plan_then_backed_up_idempotent_restore(self):
        original=self.db.read_bytes();source=self.review.read_bytes()
        self.assertEqual(2,module.restore(self.db,self.review,[0,1])['selected']);self.assertEqual(original,self.db.read_bytes());self.assertFalse(self.backup.exists())
        self.assertEqual(2,module.restore(self.db,self.review,[0,1],apply=True,backup=self.backup)['added'])
        with closing(sqlite3.connect(self.backup)) as old:
            self.assertEqual(1,old.execute('SELECT COUNT(*) FROM schema_version').fetchone()[0])
        with closing(sqlite3.connect(self.db)) as db:
            self.assertEqual(2,db.execute('SELECT COUNT(*) FROM companion_interest').fetchone()[0]);self.assertEqual(0,db.execute("SELECT enabled FROM companion_interest WHERE topic='摄影'").fetchone()[0]);self.assertEqual('原有故事',db.execute("SELECT title FROM conversation WHERE id='keep'").fetchone()[0])
        self.assertEqual(0,module.restore(self.db,self.review,[0,1],apply=True,backup=self.root/'backup-again.sqlite')['added']);self.assertEqual(source,self.review.read_bytes())
    def test_unknown_schema_and_existing_backup_fail_without_changes(self):
        self.backup.touch();before=self.db.read_bytes()
        with self.assertRaises(ValueError):module.restore(self.db,self.review,[0,1],apply=True,backup=self.backup)
        self.assertEqual(before,self.db.read_bytes())
        with closing(sqlite3.connect(self.db)) as db:db.execute("UPDATE schema_version SET checksum='changed'");db.commit()
        before=self.db.read_bytes()
        with self.assertRaises(ValueError):module.restore(self.db,self.review,[0,1],apply=True,backup=self.root/'new.sqlite')
        self.assertEqual(before,self.db.read_bytes());self.assertFalse((self.root/'new.sqlite').exists())

if __name__=='__main__':unittest.main()
