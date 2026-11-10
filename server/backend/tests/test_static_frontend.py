from __future__ import annotations

import httpx
import pytest
from fastapi import FastAPI

from main import SpaStaticFiles


@pytest.mark.asyncio
async def test_spa_static_files_fall_back_for_browser_routes_not_api_routes(tmp_path):
    (tmp_path / "index.html").write_text("<h1>HealthQuery</h1>", encoding="utf-8")
    app = FastAPI()
    app.mount("/", SpaStaticFiles(directory=tmp_path, html=True), name="frontend")

    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        browser_route = await client.get("/activity")
        api_route = await client.get("/api/not-found")

    assert browser_route.status_code == 200
    assert "HealthQuery" in browser_route.text
    assert api_route.status_code == 404
