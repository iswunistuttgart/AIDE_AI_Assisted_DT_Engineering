FROM python:3.12-slim
WORKDIR /adapter
COPY requirements.txt adapter.py ./
RUN pip install --no-cache-dir --requirement requirements.txt
CMD ["python", "-u", "adapter.py"]
