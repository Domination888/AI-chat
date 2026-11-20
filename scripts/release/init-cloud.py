#!/usr/bin/env python3
"""Write bootstrap secrets to a private file; never print them to logs."""
from pathlib import Path
import secrets
import base64

root = Path(__file__).resolve().parents[2]
file = root / "deployment/cloud/.env"
if file.exists():
    raise SystemExit("Existing .env retained; edit it directly.")
text = (file.parent / ".env.example").read_text()
values = {
    "STORY_ADMIN_PASSWORD": secrets.token_urlsafe(24),
    "STORY_ADMIN_TOTP_SECRET": base64.b32encode(secrets.token_bytes(20)).decode().rstrip("="),
    "STORY_DATABASE_PASSWORD": secrets.token_urlsafe(24),
    "STORY_ENCRYPTION_KEY": base64.b64encode(secrets.token_bytes(32)).decode(),
    "STORY_SIGNING_KEY": secrets.token_urlsafe(32),
    "STORY_S3_ACCESS_KEY": "story-" + secrets.token_hex(8),
    "STORY_S3_SECRET_KEY": secrets.token_urlsafe(32),
}
for key, value in values.items():
    text = text.replace(key + "=\n", key + "=" + value + "\n")
file.write_text(text)
file.chmod(0o600)
print("Private deployment configuration written:", file)
print("Fill in domain, administrator email, SMTP and platform model endpoints before deployment.")
