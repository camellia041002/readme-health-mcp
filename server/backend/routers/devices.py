from __future__ import annotations

import ipaddress
from typing import Any, Literal
from urllib.parse import quote, urlsplit, urlunsplit

from app_settings import get_settings
from fastapi import APIRouter, Depends, Header, HTTPException, Request, status
from pydantic import BaseModel, Field, field_validator, model_validator
from services.browser_auth import require_browser_session
from services.device_sync import (
    BatchConflictError,
    DeviceSyncError,
    authenticate_device,
    create_enrollment_token,
    exchange_enrollment_token,
    get_device_sync_history,
    list_reader_access,
    get_sync_inspection,
    list_devices,
    list_sync_runs,
    process_change_batch,
    revoke_device,
)

router = APIRouter(tags=["devices"], include_in_schema=False)


class EnrollmentCreateRequest(BaseModel):
    label: str | None = Field(default=None, max_length=120)
    server_url: str | None = Field(default=None, max_length=2_048)


class EnrollmentExchangeRequest(BaseModel):
    token: str = Field(..., min_length=20, max_length=256)
    device_name: str = Field(..., min_length=1, max_length=120)
    app_version: str | None = Field(default=None, max_length=80)
    platform: Literal["android"] = "android"
    device_model: str | None = Field(default=None, max_length=160)


class ChangeEnvelope(BaseModel):
    operation: Literal["upsert", "delete"]
    record_type: str = Field(..., min_length=1, max_length=180, pattern=r"^[A-Za-z0-9_.]+$")
    record_id: str = Field(..., min_length=1, max_length=512)
    payload: dict[str, Any] | None = None

    @model_validator(mode="after")
    def validate_payload_for_operation(self):
        if self.operation == "upsert" and self.payload is None:
            raise ValueError("upsert records require a payload")
        if self.operation == "delete" and self.payload is not None:
            raise ValueError("delete records must not contain a payload")
        return self


class DeviceSyncRequest(BaseModel):
    batch_id: str = Field(..., min_length=8, max_length=160)
    protocol_version: Literal[1] = 1
    mode: Literal["snapshot", "changes"]
    records: list[ChangeEnvelope]
    next_change_token: str | None = Field(default=None, max_length=16_384)
    app_version: str | None = Field(default=None, max_length=80)
    # Run metadata is optional so protocol-version-1 clients that predate
    # cloud-visible run history continue to submit the same envelope.
    run_id: str | None = Field(
        default=None,
        min_length=1,
        max_length=128,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$",
    )
    run_kind: Literal["initial", "automatic", "incremental", "manual", "full"] | None = None
    run_started_at: str | None = Field(default=None, max_length=80)
    run_total_records: int | None = Field(default=None, ge=0)
    run_processed_records: int | None = Field(default=None, ge=0)
    run_completed: bool | None = None
    run_duration_ms: int | None = Field(default=None, ge=0)
    run_current_record_type: str | None = Field(
        default=None,
        max_length=180,
        pattern=r"^[A-Za-z0-9_.]+$",
    )

    @field_validator("run_started_at")
    @classmethod
    def validate_run_started_at(cls, value: str | None) -> str | None:
        if value is None:
            return value
        from datetime import datetime

        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as exc:
            raise ValueError("run_started_at must be an ISO-8601 timestamp") from exc
        if parsed.tzinfo is None:
            raise ValueError("run_started_at must include a timezone")
        return value


def _public_server_url(request: Request, configured: str | None) -> str:
    settings = get_settings()
    if settings.production and settings.public_url:
        raw = settings.public_url
    elif configured:
        raw = configured.strip()
    else:
        scheme = request.url.scheme
        host = request.headers.get("host", request.url.netloc)
        raw = f"{scheme}://{host}"
    parsed = urlsplit(raw)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc or parsed.username or parsed.password:
        raise HTTPException(status_code=422, detail="server_url must be an absolute HTTP(S) origin")
    if parsed.path not in {"", "/"} or parsed.query or parsed.fragment:
        raise HTTPException(status_code=422, detail="server_url must be an origin without a path, query, or fragment")
    hostname = parsed.hostname or ""
    insecure_local = hostname == "localhost" or hostname.endswith((".localhost", ".local"))
    if not insecure_local:
        try:
            address = ipaddress.ip_address(hostname)
            insecure_local = (
                address.is_private
                or address.is_loopback
                or address.is_link_local
                or address in ipaddress.ip_network("100.64.0.0/10")
            )
        except ValueError:
            pass
    if parsed.scheme == "http" and not insecure_local:
        raise HTTPException(status_code=422, detail="Public device enrollment URLs must use HTTPS")
    return urlunsplit((parsed.scheme, parsed.netloc, "", "", ""))


async def _create_enrollment(body: EnrollmentCreateRequest, request: Request) -> dict[str, Any]:
    created = await create_enrollment_token(label=body.label)
    server_url = _public_server_url(request, body.server_url)
    pairing_url = f"{server_url}/pair/android#token={quote(str(created.pop('token')), safe='')}"
    return {**created, "pairing_url": pairing_url, "server_url": server_url}


@router.get("/api/devices", dependencies=[Depends(require_browser_session)])
async def devices_list() -> dict[str, Any]:
    return {"devices": await list_devices()}


@router.post(
    "/api/devices",
    dependencies=[Depends(require_browser_session)],
    status_code=status.HTTP_201_CREATED,
)
async def devices_create_alias(body: EnrollmentCreateRequest, request: Request) -> dict[str, Any]:
    return await _create_enrollment(body, request)


@router.post(
    "/api/devices/enrollment-tokens",
    dependencies=[Depends(require_browser_session)],
    status_code=status.HTTP_201_CREATED,
)
async def enrollment_create(body: EnrollmentCreateRequest, request: Request) -> dict[str, Any]:
    return await _create_enrollment(body, request)


@router.delete("/api/devices/{device_id}", dependencies=[Depends(require_browser_session)])
async def device_revoke(device_id: str) -> dict[str, Any]:
    if not await revoke_device(device_id):
        raise HTTPException(status_code=404, detail="Active device not found")
    return {"status": "revoked", "device_id": device_id}


@router.post("/api/device-enrollment/exchange")
async def enrollment_exchange(body: EnrollmentExchangeRequest) -> dict[str, Any]:
    exchanged = await exchange_enrollment_token(
        token=body.token,
        device_name=body.device_name.strip(),
        app_version=body.app_version,
        platform=body.platform,
        device_model=body.device_model,
    )
    if exchanged is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Enrollment credential is invalid, expired, or already used",
        )
    return exchanged


async def require_device(authorization: str = Header(default="")) -> dict[str, Any]:
    if not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="Missing device bearer credential")
    token = authorization.split(" ", 1)[1].strip()
    device = await authenticate_device(token)
    if device is None:
        raise HTTPException(status_code=401, detail="Invalid or revoked device credential")
    return device


@router.post("/api/device-sync/v1/changes")
async def device_changes_sync(
    body: DeviceSyncRequest,
    device: dict[str, Any] = Depends(require_device),
) -> dict[str, Any]:
    try:
        return await process_change_batch(
            device_id=device["device_id"],
            payload=body.model_dump(mode="json"),
        )
    except BatchConflictError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    except DeviceSyncError as exc:
        raise HTTPException(status_code=413, detail=str(exc)) from exc


@router.get("/api/sync/inspection", dependencies=[Depends(require_browser_session)])
async def sync_inspection(
    batch_limit: int = 50,
    record_limit: int = 100,
    record_type: str | None = None,
) -> dict[str, Any]:
    if not 1 <= batch_limit <= 200 or not 1 <= record_limit <= 500:
        raise HTTPException(status_code=422, detail="inspection limits are out of range")
    if record_type is not None and (not record_type or len(record_type) > 180):
        raise HTTPException(status_code=422, detail="invalid record type")
    return await get_sync_inspection(
        batch_limit=batch_limit,
        record_limit=record_limit,
        record_type=record_type,
    )


@router.get("/api/sync/runs", dependencies=[Depends(require_browser_session)])
async def sync_runs(limit: int = 50, device_id: str | None = None) -> dict[str, Any]:
    return {"runs": await list_sync_runs(limit=limit, device_id=device_id)}


@router.get("/api/device-sync/v1/history")
async def device_sync_history(
    limit: int = 50,
    device: dict[str, Any] = Depends(require_device),
) -> dict[str, Any]:
    return {"runs": await get_device_sync_history(device_id=device["device_id"], limit=limit)}


@router.get("/api/device-sync/v1/reader-access")
async def device_reader_access(
    limit: int = 50,
    device: dict[str, Any] = Depends(require_device),
) -> dict[str, Any]:
    del device
    return {"events": await list_reader_access(limit=limit)}
