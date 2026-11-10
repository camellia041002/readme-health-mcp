from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest


BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

# Keep module-level imports of ``main.app`` deterministic while exercising the
# production security middleware.  This is a test-only Argon2id hash for the
# password used by the browser-auth helpers below.
TEST_ADMIN_PASSWORD = "correct horse battery staple"
os.environ.setdefault(
    "HEALTHQUERY_ADMIN_PASSWORD_HASH",
    "$argon2id$v=19$m=65536,t=3,p=4$8Zl/l0xKqO19PBMwqH4z1w$jRpnvyS0XDgXnxi6RjdYo1ZJ9IR3jsA/BcnyUt86E80",
)
os.environ.setdefault("HEALTHQUERY_ALLOWED_HOSTS", "localhost,127.0.0.1,testserver,test,health.example")


@pytest.fixture(autouse=True)
def isolated_healthquery_env(tmp_path, monkeypatch):
    monkeypatch.setenv("DB_PATH", str(tmp_path / "healthquery.db"))
    monkeypatch.setenv("HEALTHQUERY_INGEST_TOKEN", "ingest-token")
    monkeypatch.setenv("HEALTHQUERY_READ_TOKEN", "read-token")
    monkeypatch.setenv("HEALTHQUERY_LOG_LEVEL", "INFO")
    monkeypatch.setenv("HEALTHQUERY_AUTH_HEADER", "X-Webhook-Token")
    monkeypatch.setenv("HEALTHQUERY_ADMIN_PASSWORD_HASH", os.environ["HEALTHQUERY_ADMIN_PASSWORD_HASH"])
    monkeypatch.setenv("HEALTHQUERY_SESSION_SECRET", "test-session-secret-" + "s" * 48)
    from services.http_security import _RATE_STORAGE

    _RATE_STORAGE.reset()
