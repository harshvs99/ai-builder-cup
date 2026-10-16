# syntax=docker/dockerfile:1
# Build context: the Relay folder root.
#   docker build -f infra/Dockerfile -t relay .

# ---- Stage 1: dashboard (static files) -------------------------------------
FROM node:22-slim AS dashboard
WORKDIR /dashboard

COPY dashboard/package.json dashboard/package-lock.json ./
RUN npm ci

COPY dashboard/ ./
# Empty API base: the dashboard calls the API on its own origin.
RUN VITE_API_BASE="" npm run build

# ---- Stage 2: API + static dashboard ----------------------------------------
FROM python:3.12-slim AS runtime

COPY --from=ghcr.io/astral-sh/uv:0.11 /uv /uvx /usr/local/bin/

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PATH="/app/.venv/bin:$PATH"

WORKDIR /app

# Dependencies first so code changes do not bust this layer.
COPY backend/pyproject.toml backend/uv.lock ./
RUN uv sync --frozen --no-dev

COPY backend/app ./app
COPY --from=dashboard /dashboard/dist ./static

ENV RELAY_STATIC_DIR=/app/static \
    PORT=8080

RUN useradd --no-create-home --uid 10001 relay
USER relay

EXPOSE 8080
CMD ["sh", "-c", "exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8080}"]
