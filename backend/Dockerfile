# Build context: repository root (the backend needs the MontiCore languages).
FROM gradle:8.10.2-jdk21 AS languages
WORKDIR /languages
COPY languages/ .
# A jar in languages/prebuilt/ is used as is, so the build needs no access to the MontiCore
# Nexus; without it the languages are compiled. Delete the jar after changes to languages/.
RUN if [ -f prebuilt/dtlang.jar ]; then \
      echo "Using languages/prebuilt/dtlang.jar"; \
      mkdir -p build/libs && cp prebuilt/dtlang.jar build/libs/dtlang.jar; \
    else \
      gradle --no-daemon -q cliJar; \
    fi

FROM python:3.12-slim
COPY --from=eclipse-temurin:21-jre /opt/java/openjdk /opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:${PATH}"
WORKDIR /app
COPY backend/requirements.txt ./
RUN pip install --no-cache-dir --requirement requirements.txt
COPY --from=languages /languages/build/libs/dtlang.jar /app/dtlang.jar
COPY backend/app ./app
CMD ["python", "-m", "uvicorn", "app.main:app", "--host=0.0.0.0", "--port=8000"]
