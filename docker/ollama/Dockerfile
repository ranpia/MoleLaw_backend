ARG OLLAMA_VERSION=0.40.3
FROM ollama/ollama:${OLLAMA_VERSION}

LABEL org.opencontainers.image.title="MoleLaw CPU embedding lab"
ENV OLLAMA_HOST=0.0.0.0:11434 \
    OLLAMA_MAX_LOADED_MODELS=1 \
    OLLAMA_NUM_PARALLEL=1 \
    OLLAMA_MAX_QUEUE=8 \
    OLLAMA_KEEP_ALIVE=5m \
    OLLAMA_NO_CLOUD=1 \
    OLLAMA_VULKAN=0 \
    CUDA_VISIBLE_DEVICES=-1

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=12 \
    CMD OLLAMA_HOST=127.0.0.1:11434 ollama list > /dev/null || exit 1

EXPOSE 11434
