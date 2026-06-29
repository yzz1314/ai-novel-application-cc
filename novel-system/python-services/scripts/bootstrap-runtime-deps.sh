#!/bin/sh
set -eu

if ! python - <<'PY'
import importlib.metadata as metadata
import sys

required = {
    "litellm": "1.17.0",
    "openai": "1.10.0",
    "anthropic": "0.8.1",
    "httpx": "0.26.0",
}

missing = []
for package, version in required.items():
    try:
        installed = metadata.version(package)
    except metadata.PackageNotFoundError:
        missing.append(f"{package}=={version}")
        continue
    if installed != version:
        missing.append(f"{package}=={version}")

if missing:
    print(" ".join(missing))
    sys.exit(1)
sys.exit(0)
PY
then
  pip install --no-cache-dir litellm==1.17.0 openai==1.10.0 anthropic==0.8.1 httpx==0.26.0
fi
