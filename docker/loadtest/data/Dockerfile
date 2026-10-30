FROM python:3.12-slim
WORKDIR /tool
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY seed.py .
ENTRYPOINT ["python", "-u", "/tool/seed.py"]
