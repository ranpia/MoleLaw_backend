FROM python:3.12-slim
ARG TORCH_INDEX_URL=https://download.pytorch.org/whl/cpu
WORKDIR /app
RUN pip install --no-cache-dir torch==2.7.1 --index-url ${TORCH_INDEX_URL}
COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt
COPY server.py /app/server.py
COPY reranker.py /app/reranker.py
COPY monitoring.py /app/monitoring.py
ENV EMBEDDING_QUEUE_CAPACITY=4 EMBEDDING_QUEUE_WAIT_SECONDS=5
HEALTHCHECK --interval=10s --timeout=3s --start-period=90s --retries=6 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:11434/api/version', timeout=2)"
EXPOSE 11434
CMD ["python", "-u", "/app/server.py"]
