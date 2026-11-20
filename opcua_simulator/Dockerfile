FROM python:3.12-slim
WORKDIR /simulator
RUN pip install --no-cache-dir asyncua==1.1.5
COPY opcua_simulator.py opcua_healthcheck.py ./
HEALTHCHECK --interval=5s --timeout=5s --retries=12 CMD python opcua_healthcheck.py
CMD ["python", "-u", "opcua_simulator.py"]
