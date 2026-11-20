CREATE TABLE account (id INTEGER PRIMARY KEY, email TEXT UNIQUE NOT NULL, username TEXT NOT NULL, password TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'active', admin BOOLEAN NOT NULL DEFAULT FALSE, created_at TEXT NOT NULL);
--statement--
CREATE TABLE role_card (id INTEGER PRIMARY KEY, owner_id INTEGER, name TEXT NOT NULL, avatar TEXT NOT NULL DEFAULT '', profile TEXT NOT NULL DEFAULT '', background TEXT NOT NULL DEFAULT '', personality TEXT NOT NULL DEFAULT '', example_dialogue TEXT NOT NULL DEFAULT '', greeting TEXT NOT NULL DEFAULT '', voice_id TEXT NOT NULL DEFAULT '', role_code TEXT NOT NULL DEFAULT '', persona_card_path TEXT NOT NULL DEFAULT '', memos_cube_id TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);
--statement--
CREATE TABLE conversation (id TEXT PRIMARY KEY, user_id INTEGER NOT NULL, role_id INTEGER NOT NULL, title TEXT NOT NULL DEFAULT '新的相遇', created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);
--statement--
CREATE INDEX conversation_owner ON conversation(user_id,role_id,created_at);
--statement--
CREATE TABLE history (id INTEGER PRIMARY KEY, conversation_id TEXT NOT NULL, sender TEXT NOT NULL, content TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);
--statement--
CREATE INDEX history_chapter ON history(conversation_id,id);
--statement--
CREATE TABLE rp_world (id TEXT PRIMARY KEY, user_id INTEGER NOT NULL, role_id INTEGER NOT NULL, state_json TEXT NOT NULL, initial_json TEXT NOT NULL);
--statement--
CREATE TABLE rp_session (conversation_id TEXT PRIMARY KEY, world_id TEXT NOT NULL);
--statement--
CREATE INDEX rp_world_idx ON rp_session(world_id);
--statement--
CREATE TABLE rp_turn (id INTEGER PRIMARY KEY, world_id TEXT NOT NULL, conversation_id TEXT NOT NULL, history_id BIGINT NOT NULL, state_json TEXT NOT NULL, user_text TEXT NOT NULL, assistant_text TEXT NOT NULL, status TEXT NOT NULL, images_json TEXT NOT NULL, bookmarked BOOLEAN NOT NULL DEFAULT FALSE, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);
--statement--
CREATE INDEX rp_turn_world ON rp_turn(world_id,id);
--statement--
CREATE INDEX rp_turn_history ON rp_turn(conversation_id,history_id);
--statement--
CREATE TABLE rp_attachment (history_id BIGINT PRIMARY KEY, conversation_id TEXT NOT NULL, images_json TEXT NOT NULL);
--statement--
CREATE TABLE rp_audio_sentence (stream_id TEXT NOT NULL, conversation_id TEXT NOT NULL, idx INTEGER NOT NULL, text TEXT NOT NULL, heard BOOLEAN NOT NULL DEFAULT FALSE, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(stream_id,idx));
--statement--
CREATE TABLE memory_outbox (id TEXT PRIMARY KEY, conversation_id TEXT, user_id TEXT, role_id INTEGER, kind TEXT, candidate TEXT, attempts INTEGER DEFAULT 0, available_at TEXT DEFAULT CURRENT_TIMESTAMP, status TEXT DEFAULT 'pending');
--statement--
CREATE TABLE memory_entry (id INTEGER PRIMARY KEY, world_id TEXT NOT NULL, history_id BIGINT NOT NULL UNIQUE, content TEXT NOT NULL, embedding BLOB NOT NULL, model TEXT NOT NULL);
--statement--
CREATE INDEX memory_world ON memory_entry(world_id,history_id);
--statement--
CREATE TABLE index_queue (history_id BIGINT PRIMARY KEY, attempts INTEGER NOT NULL DEFAULT 0, available_at TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'pending');
--statement--
CREATE TABLE attachment (id TEXT PRIMARY KEY, user_id INTEGER NOT NULL, media_type TEXT NOT NULL, size BIGINT NOT NULL, created_at TEXT NOT NULL);
--statement--
CREATE TABLE attachment_link (conversation_id TEXT NOT NULL, attachment_id TEXT NOT NULL, PRIMARY KEY(conversation_id,attachment_id));
--statement--
CREATE TABLE daily_usage (user_id INTEGER NOT NULL, day TEXT NOT NULL, provider TEXT NOT NULL, requests INTEGER NOT NULL DEFAULT 0, input_tokens BIGINT NOT NULL DEFAULT 0, output_tokens BIGINT NOT NULL DEFAULT 0, estimated BOOLEAN NOT NULL DEFAULT FALSE, PRIMARY KEY(user_id,day,provider));
--statement--
CREATE TABLE model_connection (user_id INTEGER PRIMARY KEY, base_url TEXT NOT NULL, model TEXT NOT NULL, key_cipher TEXT NOT NULL, updated_at TEXT NOT NULL);
--statement--
CREATE TABLE runtime_setting (name TEXT PRIMARY KEY, value TEXT NOT NULL);
--statement--
CREATE VIRTUAL TABLE memory_fts USING fts5(content, content='memory_entry', content_rowid='id', tokenize='trigram');
--statement--
CREATE TRIGGER memory_insert AFTER INSERT ON memory_entry BEGIN INSERT INTO memory_fts(rowid,content) VALUES(new.id,new.content); END;
--statement--
CREATE TRIGGER memory_delete AFTER DELETE ON memory_entry BEGIN INSERT INTO memory_fts(memory_fts,rowid,content) VALUES('delete',old.id,old.content); END;
--statement--
CREATE TRIGGER memory_update AFTER UPDATE ON memory_entry BEGIN INSERT INTO memory_fts(memory_fts,rowid,content) VALUES('delete',old.id,old.content); INSERT INTO memory_fts(rowid,content) VALUES(new.id,new.content); END;
