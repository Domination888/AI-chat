#!/usr/bin/env python3
"""Read-only MySQL export. No account passwords or model keys leave the old database."""
import argparse
import datetime
import json
import os
from pathlib import Path
import pymysql

p = argparse.ArgumentParser()
p.add_argument("output", type=Path)
p.add_argument("--user-id", type=int, nargs="+", required=True)
p.add_argument("--memories", type=Path, help="Optional MemOS text export, preserved for review")
p.add_argument("--all-roles", action="store_true", help="Preserve unused cards in a personal database")
args = p.parse_args()
placeholders = ",".join(["%s"] * len(args.user_id))
condition = "user_id IN (" + placeholders + ")"
connection = pymysql.connect(host=os.getenv("LEGACY_MYSQL_HOST", "127.0.0.1"), port=int(os.getenv("LEGACY_MYSQL_PORT", "3306")), user=os.getenv("LEGACY_MYSQL_USER", "root"), password=os.getenv("LEGACY_MYSQL_PASSWORD", ""), database=os.getenv("LEGACY_MYSQL_DATABASE", "ai_chat"), charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor)
data = {"format": "legacy-ai-chat-v1"}
with connection, connection.cursor() as cursor:
    cursor.execute("SET TRANSACTION READ ONLY")
    cursor.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT")
    cursor.execute("SELECT * FROM conversation WHERE " + condition, tuple(args.user_id))
    data["conversations"] = cursor.fetchall()
    cursor.execute("SELECT * FROM role_card" if args.all_roles else "SELECT * FROM role_card WHERE id IN(SELECT role_id FROM conversation WHERE " + condition + ")", () if args.all_roles else tuple(args.user_id))
    data["roles"] = cursor.fetchall()
    cursor.execute("SHOW TABLES")
    tables = {next(iter(row.values())) for row in cursor.fetchall()}
    for table in ["history", "rp_session", "rp_turn", "rp_attachment", "rp_audio_sentence"]:
        if table not in tables:
            data[table] = []
            continue
        cursor.execute("SELECT * FROM " + table + " WHERE conversation_id IN(SELECT id FROM conversation WHERE " + condition + ")", tuple(args.user_id))
        data[table] = cursor.fetchall()
    if "rp_world" in tables:
        cursor.execute("SELECT * FROM rp_world WHERE " + condition, tuple(args.user_id))
        data["rp_world"] = cursor.fetchall()
    if "proactive_interest" in tables:
        cursor.execute("SELECT * FROM proactive_interest WHERE " + condition, tuple(args.user_id))
        data["companion_interests"] = cursor.fetchall()
    connection.rollback()
if args.memories:
    data["legacy_memories"] = json.loads(args.memories.read_text())
args.output.write_text(json.dumps(data, ensure_ascii=False, indent=2, default=lambda v: v.isoformat() if isinstance(v, (datetime.date, datetime.datetime)) else str(v)))
args.output.chmod(0o600)
print("Exported", len(data["conversations"]), "conversations to", args.output)
