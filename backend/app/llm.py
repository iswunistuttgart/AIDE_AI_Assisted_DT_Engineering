"""A plain-text call to the chat completions API of Mistral, Groq or NVIDIA, chosen by AI_PROVIDER. The answer is a textual
specification that the language tool parses and checks; it is never trusted as is.
Every call is logged with the model name and version."""

from __future__ import annotations

import hashlib
import json
import os
import re
import time
from datetime import datetime, timezone

import requests

# Provider: endpoint, API key variable, default model and name of the seed parameter.
PROVIDERS = {
    "mistral": ("https://api.mistral.ai/v1/chat/completions", "MISTRAL_API_KEY", "ministral-14b-2512", "random_seed"),
    "groq": ("https://api.groq.com/openai/v1/chat/completions", "GROQ_API_KEY", "openai/gpt-oss-120b", "seed"),
    "nvidia": ("https://integrate.api.nvidia.com/v1/chat/completions", "NVIDIA_API_KEY", "nvidia/nemotron-3-ultra-550b-a55b", "seed"),
}
RATE_LIMIT_RETRIES = 6  # Waits 1, 2, 4, 8, 16 and 32 s, longer than the one-minute window of the API limit.


def provider() -> str:
    name = os.getenv("AI_PROVIDER") or "groq"
    if name not in PROVIDERS:
        raise RuntimeError(f"AI_PROVIDER must be one of {', '.join(PROVIDERS)}, not {name!r}.")
    return name


def model() -> str:
    return os.getenv("AI_MODEL") or PROVIDERS[provider()][2]


def complete(system_prompt: str, user_prompt: str | list[dict[str, str]]) -> str:
    """The user prompt is one message or a conversation of user and assistant messages.
    API and network failures raise RuntimeError, which the front end shows as a system error."""
    name = provider()
    endpoint, key_variable, _, seed_parameter = PROVIDERS[name]
    api_key = os.getenv(key_variable)
    if not api_key:
        raise RuntimeError(f"{key_variable} is not set.")
    conversation = [{"role": "user", "content": user_prompt}] if isinstance(user_prompt, str) else user_prompt
    messages = [{"role": "system", "content": system_prompt}, *conversation]
    payload = {
        "model": model(),
        "messages": messages,
        "max_tokens": int(os.getenv("AI_MAX_OUTPUT_TOKENS", "20000")),
        "temperature": 0,
    }
    if os.getenv("AI_SEED"):
        payload[seed_parameter] = int(os.environ["AI_SEED"])
    # Parallel calls and revisions can exceed the rate limit of the API key; such a call waits and retries.
    for attempt in range(RATE_LIMIT_RETRIES + 1):
        try:
            response = requests.post(
                endpoint,
                headers={"Authorization": f"Bearer {api_key}"},
                json=payload,
                timeout=int(os.getenv("AI_TIMEOUT_SECONDS", "600")),
            )
        except requests.RequestException as exc:
            raise RuntimeError(f"{name} request failed: {exc}") from exc
        if response.status_code != 429 or attempt == RATE_LIMIT_RETRIES:
            break
        time.sleep(2 ** attempt)
    if not response.ok:
        raise RuntimeError(f"{name} request failed ({response.status_code}): {response.text[:500]}")
    answer = response.json()
    print(json.dumps({
        "time": datetime.now(timezone.utc).isoformat(),
        "provider": name,
        "model": model(),
        "modelVersion": answer.get("model"),
        "promptHash": hashlib.sha256(json.dumps(messages).encode("utf-8")).hexdigest()[:16],
        "usage": answer.get("usage"),
    }), flush=True)
    content = answer["choices"][0]["message"]["content"]
    if isinstance(content, list):
        # Reasoning models return chunks; only the text chunks are the answer.
        content = "".join(chunk.get("text", "") for chunk in content if chunk.get("type") == "text")
    return strip_fences(content)


def strip_fences(text: str) -> str:
    """Removes a Markdown code fence around the answer, if the model added one. A fence without
    its own line, such as ```workflow ..., is removed without the text that follows it."""
    match = re.search(r"```[a-zA-Z]*\n(.*?)```", text, re.DOTALL)
    text = match.group(1) if match else text.replace("```", "")
    # Markdown separators between workflows (---) are valid in none of the languages.
    return re.sub(r"(?m)^[ \t]*(-{3,}|\*{3,})[ \t]*$", "", text).strip() + "\n"
