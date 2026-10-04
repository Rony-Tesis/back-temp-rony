"""Native adapter values only. Workflow and HTTP contracts belong to Java."""
from dataclasses import dataclass
import math

HOME = (0., -162.94, 212.8)
LABELS = {"plastic": "Plástico", "cardboard": "Cartón", "aluminum": "Aluminio"}

@dataclass(frozen=True)
class Observation:
    jpeg: bytes
    detection: dict | None
    stable_frames: int = 0
    target_detection: dict | None = None

@dataclass(frozen=True)
class MotionStep:
    stage: int
    label: str
    position: tuple[float, float, float] | None
    duration: float
    gripper: int | None = None

def validate_position(position):
    x, y, z = position
    if not all(math.isfinite(v) for v in position) or not (-100 <= x <= 100 and -240 <= y <= -90 and 90 <= z <= 213):
        raise ValueError('Objetivo fuera de la zona de prueba.')
    if math.hypot(x, y) < 50:
        raise ValueError('Objetivo demasiado próximo a la base.')
