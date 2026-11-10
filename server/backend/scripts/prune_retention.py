from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path


BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from db.database import init_db
from services.retention import prune_expired_health_data


async def main() -> None:
    await init_db()
    result = await prune_expired_health_data(compact=True)
    print(json.dumps(result, separators=(",", ":"), sort_keys=True))


if __name__ == "__main__":
    asyncio.run(main())
