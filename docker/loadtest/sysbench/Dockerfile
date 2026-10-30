FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends sysbench mariadb-client python3 ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /bench
COPY run.py hotrow.lua ./
ENTRYPOINT ["python3", "-u", "/bench/run.py"]
