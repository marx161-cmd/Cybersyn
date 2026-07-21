import json
from pathlib import Path
from typing import Any


def load_config(path: str) -> dict[str, Any]:
    cfg_path = Path(path)
    with cfg_path.open("r", encoding="utf-8") as fh:
        return json.load(fh)
