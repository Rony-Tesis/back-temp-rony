"""One profile exported from the current notebook; never execute notebook cells."""
import json
import hashlib
import math
import os
from pathlib import Path


def load_profile(path=None):
    default = Path(__file__).resolve().parents[2] / 'rony-yolo-isaac/runtime/rony_profile.json'
    profile_path = Path(path or os.environ.get('RONY_RUNTIME_PROFILE', default)).resolve()
    data = json.loads(profile_path.read_text(encoding='utf-8'))
    if data.get('schemaVersion') != 1:
        raise ValueError('Perfil de notebook incompatible.')
    return data


def robot_calibration(packet):
    """Verify the agent's read-only ROS snapshot, never fall back to notebook K/R/T."""
    parameters = packet.get('parameters') if isinstance(packet, dict) else None
    if not isinstance(parameters, dict) or set(parameters) != {'K', 'R', 'T'}:
        raise ValueError('Calibración del brazo no disponible.')
    for key, rows, columns in (('K', 3, 3), ('R', 3, 1), ('T', 3, 1)):
        value = parameters[key]
        if (not isinstance(value, list) or len(value) != rows
                or any(not isinstance(row, list) or len(row) != columns
                       or any(not isinstance(n, (int, float)) or isinstance(n, bool)
                              or not math.isfinite(n) for n in row) for row in value)):
            raise ValueError('Calibración del brazo inválida: ' + key)
    raw = json.dumps(parameters, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')
    sha = hashlib.sha256(raw).hexdigest()
    if packet.get('sha256') != sha:
        raise ValueError('La calibración recibida no coincide con su identificador.')
    return json.loads(raw), sha
