# syntax=docker/dockerfile:1

# Build the React dashboard once and serve it from the API container.
FROM node:22-alpine AS frontend-builder
WORKDIR /app

COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/ ./
ARG VITE_API_BASE_URL=/api
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

FROM python:3.11-slim AS runtime
WORKDIR /app

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    HEALTHQUERY_ENV=production \
    DB_PATH=/app/data/healthquery.db \
    HEALTHQUERY_LOG_LEVEL=INFO \
    FRONTEND_DIST_PATH=/app/frontend/dist

COPY backend/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

COPY healthquery_client ./healthquery_client
RUN pip install --no-cache-dir ./healthquery_client

COPY backend/ ./
COPY --from=frontend-builder /app/dist ./frontend/dist
COPY scripts/docker-entrypoint.sh /usr/local/bin/healthquery-entrypoint
COPY scripts/docker-healthcheck.py /usr/local/bin/healthquery-healthcheck.py

RUN useradd --create-home --uid 1001 --shell /bin/bash healthquery \
    && mkdir -p /app/data \
    && chown healthquery:healthquery /app/data \
    && chmod 0555 /usr/local/bin/healthquery-entrypoint /usr/local/bin/healthquery-healthcheck.py

USER 1001:1001
VOLUME ["/app/data"]
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["python", "/usr/local/bin/healthquery-healthcheck.py"]

ENTRYPOINT ["healthquery-entrypoint"]
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000", "--no-server-header", "--proxy-headers", "--forwarded-allow-ips", "127.0.0.1"]
