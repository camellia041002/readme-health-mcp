#!/usr/local/bin/python
"""Probe the local app while honoring production TrustedHostMiddleware."""

import os
import urllib.request


allowed_host = os.environ.get("HEALTHQUERY_ALLOWED_HOSTS", "").split(",", 1)[0].strip()
if not allowed_host:
    raise SystemExit("HEALTHQUERY_ALLOWED_HOSTS is required for the healthcheck")

request = urllib.request.Request(
    "http://127.0.0.1:8000/api/health",
    headers={"Host": allowed_host},
)
with urllib.request.urlopen(request, timeout=3) as response:
    raise SystemExit(0 if response.status == 200 else 1)
