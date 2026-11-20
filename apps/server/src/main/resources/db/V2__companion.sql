CREATE TABLE companion_setting (user_id INTEGER PRIMARY KEY, settings_json TEXT NOT NULL);
--statement--
CREATE TABLE companion_interest (id TEXT PRIMARY KEY, user_id INTEGER NOT NULL, topic TEXT NOT NULL, sentiment TEXT NOT NULL, source TEXT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, created_at TEXT NOT NULL, UNIQUE(user_id,topic));
--statement--
CREATE TABLE companion_daily (user_id INTEGER PRIMARY KEY, digest_json TEXT NOT NULL, issue_date TEXT NOT NULL, fetched_at TEXT NOT NULL, read_at TEXT NOT NULL DEFAULT '');
