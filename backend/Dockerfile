FROM python:3.12-slim@sha256:a6e34c598f2467ed0e9a8d349809fcd8b5c603269512df273a0bb1784edc11b1
WORKDIR /app
COPY requirements.lock.txt ./
RUN pip install --no-cache-dir --require-hashes -r requirements.lock.txt
COPY openpocket_backend ./openpocket_backend
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 OPENPOCKET_DATA_DIR=/data
USER 10000:10000
CMD ["python", "-m", "uvicorn", "openpocket_backend.app:from_env", "--factory", "--host", "0.0.0.0", "--port", "8879", "--workers", "1", "--limit-concurrency", "32", "--no-access-log"]
