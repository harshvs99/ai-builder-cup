FROM python:3.12-slim
WORKDIR /srv
COPY pyproject.toml ./
COPY app ./app
RUN pip install --no-cache-dir .
ENV PORT=8080 RELAY_DB_PATH=/tmp/relay.db
CMD exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT}
