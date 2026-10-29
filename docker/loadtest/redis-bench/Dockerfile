FROM redis:7.4.11-alpine
RUN apk add --no-cache python3 py3-redis
WORKDIR /bench
COPY run.py ./
ENTRYPOINT ["python3", "-u", "/bench/run.py"]
