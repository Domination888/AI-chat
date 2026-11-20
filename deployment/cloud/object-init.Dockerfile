FROM python:3.12-slim-bookworm
COPY deployment/cloud/init-objects.py /app/init-objects.py
ENTRYPOINT ["python", "/app/init-objects.py"]
