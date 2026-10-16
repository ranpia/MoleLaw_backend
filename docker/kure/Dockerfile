FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 HF_HOME=/models HF_HUB_DISABLE_TELEMETRY=1 \
    OMP_NUM_THREADS=6 MKL_NUM_THREADS=6 TOKENIZERS_PARALLELISM=false
WORKDIR /app
COPY server.py test_server.py /app/
RUN python -m unittest test_server -q
RUN pip install --no-cache-dir torch==2.14.1 --index-url https://download.pytorch.org/whl/cpu \
    && pip install --no-cache-dir sentence-transformers==6.1.0 transformers==5.20.0
COPY verify_model.py /app/verify_model.py
EXPOSE 11434
CMD ["python", "/app/server.py"]
