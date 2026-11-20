CREATE TABLE message_context (history_id BIGINT PRIMARY KEY, conversation_id TEXT NOT NULL, context_json TEXT NOT NULL);
--statement--
CREATE TABLE proactive_candidate (id TEXT PRIMARY KEY, user_id INTEGER NOT NULL, conversation_id TEXT NOT NULL, topic TEXT NOT NULL, title TEXT NOT NULL, summary TEXT NOT NULL, sources_json TEXT NOT NULL, fingerprint TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'pending', feedback TEXT NOT NULL DEFAULT '', history_id BIGINT, created_at TEXT NOT NULL, UNIQUE(user_id,conversation_id,fingerprint));
--statement--
CREATE TABLE companion_skill (user_id INTEGER NOT NULL, name TEXT NOT NULL, manifest_json TEXT NOT NULL, PRIMARY KEY(user_id,name));
--statement--
CREATE TABLE companion_mcp (user_id INTEGER NOT NULL, id TEXT NOT NULL, config_json TEXT NOT NULL, PRIMARY KEY(user_id,id));
--statement--
CREATE TABLE memory_feedback (history_id BIGINT PRIMARY KEY, world_id TEXT NOT NULL, correction TEXT NOT NULL DEFAULT '', hidden BOOLEAN NOT NULL DEFAULT FALSE);
--statement--
CREATE TABLE character_profile (role_id INTEGER PRIMARY KEY, profile_json TEXT NOT NULL);
