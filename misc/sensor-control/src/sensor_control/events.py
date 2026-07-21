from dataclasses import dataclass, field
from typing import Any


@dataclass
class Event:
    name: str
    timestamp: float
    data: dict[str, Any] = field(default_factory=dict)
