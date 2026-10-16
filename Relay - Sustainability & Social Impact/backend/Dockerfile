FROM python:3.12-slim

COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv

ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy PYTHONUNBUFFERED=1
WORKDIR /srv

# dependencies first (cached layer)
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev

COPY app ./app

ENV PATH="/srv/.venv/bin:$PATH" \
    PORT=8080 \
    RELAY_DB=/tmp/relay.db \
    RELAY_ENV=prod

# Cloud Run sets $PORT. SQLite lives on the instance's ephemeral disk: run with max-instances=1.
CMD exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8080}
