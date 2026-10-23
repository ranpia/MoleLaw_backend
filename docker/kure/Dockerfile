# Reuse the already validated, pinned CPU runtime and model dependencies.
ARG KURE_RUNTIME_IMAGE=molelaw/kure-embedding:st6.1.0-cpu
FROM ${KURE_RUNTIME_IMAGE}
COPY server.py /app/server.py
ENV EMBEDDING_QUEUE_CAPACITY=4 EMBEDDING_QUEUE_WAIT_SECONDS=5
HEALTHCHECK --interval=10s --timeout=3s --start-period=90s --retries=6 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:11434/api/version', timeout=2)"
EXPOSE 11434
CMD ["python", "-u", "/app/server.py"]
