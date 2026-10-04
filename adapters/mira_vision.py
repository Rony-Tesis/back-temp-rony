"""One worker owns capture and inference. No SSH, ROS or actuator adapter."""
from pathlib import Path
from urllib.request import urlopen
from threading import Condition, Event, Thread
import math
import time

import cv2
import numpy as np

from worker_support import LABELS, Observation


def encode(frame) -> bytes:
    ok, encoded = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, 80])
    if not ok:
        raise ValueError("No se pudo codificar el frame.")
    return encoded.tobytes()


def pixel_to_target(u: float, v: float, width: int, height: int) -> tuple[float, float]:
    # Calibration copied from MIRA. Scale camera coordinates to its reference resolution.
    if not all(math.isfinite(n) for n in (u, v, width, height)) or width <= 0 or height <= 0:
        raise ValueError("Coordenadas inválidas.")
    if not 0 <= u < width or not 0 <= v < height:
        raise ValueError("El centro está fuera de la imagen.")
    if abs(width / height - 4 / 3) > .01:
        raise ValueError("El encuadre no coincide con la referencia de calibración 4:3.")
    k = np.array([[421.7179870605469, 0, 331.8549044912379],
                  [0, 464.7308349609375, 234.46281488904788], [0, 0, 1]])
    r, _ = cv2.Rodrigues(np.array([-.07697136252374011, 3.086054291330642, .1219708621340273]))
    inv_r = np.linalg.inv(r)
    trans = inv_r @ np.array([-16.652967984876717, 51.4273611584664, 183.827498153882])
    ray = inv_r @ np.linalg.inv(k) @ np.array([u * 640 / width, v * 480 / height, 1.])
    if abs(ray[2]) < 1e-9:
        raise ValueError("El rayo no intersecta el plano calibrado.")
    point = trans[2] / ray[2] * ray - trans
    x, y = float(point[0]), -162.94 + float(point[1])
    if not math.isfinite(x + y) or abs(x) > 1000 or abs(y) > 1000:
        raise ValueError("La proyección excede el rango de visualización.")
    return x, y


def describe_detection(category, confidence, u, v, width, height) -> dict:
    try:
        x, y = pixel_to_target(u, v, width, height)
        offset, target = {"x": x, "y": y + 162.94}, {"x": x, "y": y}
    except ValueError:
        # Detection remains visible even when calibration cannot produce a valid projection.
        offset, target = None, None
    return {"residue": LABELS[category], "shortName": LABELS[category], "category": category,
            "confidence": confidence, "pixel": {"x": u, "y": v},
            "offsetMm": offset, "targetMm": target,
            "destination": f"Contenedor de {LABELS[category].lower()} (virtual)"}


class MiraVision:
    def __init__(self, camera_url: str, model_path: Path):
        if not model_path.is_file():
            raise ValueError("No existe el modelo configurado.")
        from ultralytics import YOLO
        import torch
        self.model = YOLO(str(model_path))
        self.device = 0 if torch.cuda.is_available() else "cpu"
        self.url = camera_url
        self.capture = None
        self.previous = None
        self.stable = 0

    def _frame(self):
        if self.capture is None:
            self.capture = LatestMjpegCapture(self.url)
        jpeg = self.capture.read()
        frame = cv2.imdecode(np.frombuffer(jpeg, np.uint8), cv2.IMREAD_COLOR)
        if frame is None:
            raise ValueError("JPEG inválido.")
        return frame

    def read(self) -> Observation:
        try:
            frame = self._frame()
            result = self.model.predict(frame, imgsz=640, conf=.20, iou=.45, device=self.device, verbose=False)[0]
        except Exception:
            self.close()
            self.previous, self.stable = None, 0
            raise
        candidates = []
        visible = []
        for box in result.boxes:
            category = result.names[int(box.cls[0])]
            confidence = float(box.conf[0])
            x1, y1, x2, y2 = map(float, box.xyxy[0])
            if category not in LABELS or not math.isfinite(confidence) or confidence < .20:
                continue
            u, v = (x1 + x2) / 2, (y1 + y2) / 2
            item = describe_detection(category, confidence, u, v, frame.shape[1], frame.shape[0])
            visible.append(item)
            if category == "plastic" and confidence >= .80:
                candidates.append(((x1 + x2) / 2, (y1 + y2) / 2, confidence))
        detection = None
        if candidates:
            chosen = max(candidates, key=lambda p: p[2]) if self.previous is None else min(
                candidates, key=lambda p: math.hypot(p[0] - self.previous[0], p[1] - self.previous[1]))
            u, v, confidence = chosen
            jump = math.inf if self.previous is None else math.hypot(u - self.previous[0], v - self.previous[1])
            self.stable = self.stable + 1 if jump <= 30 else 1
            if self.previous:
                u, v = .2 * self.previous[0] + .8 * u, .2 * self.previous[1] + .8 * v
            self.previous = (u, v)
            try:
                pixel_to_target(u, v, frame.shape[1], frame.shape[0])
                detection = describe_detection("plastic", confidence, u, v, frame.shape[1], frame.shape[0])
            except ValueError:
                self.stable = 0
        else:
            self.previous, self.stable = None, 0
        # Own writable array: avoids read-only output arrays and automatic font downloads.
        annotated = frame.copy()
        for box in result.boxes:
            x1, y1, x2, y2 = map(int, box.xyxy[0])
            category = result.names[int(box.cls[0])]
            label = {"plastic": "plastico", "cardboard": "carton", "aluminum": "aluminio"}.get(category, category)
            cv2.rectangle(annotated, (x1, y1), (x2, y2), (60, 220, 80), 2)
            cv2.putText(annotated, f"{label} {float(box.conf[0]):.2f}", (x1, max(20, y1 - 7)), cv2.FONT_HERSHEY_SIMPLEX, .55, (60, 220, 80), 2)
        cv2.putText(annotated, "MIRA / CAMARA REAL", (12, 28), cv2.FONT_HERSHEY_SIMPLEX, .6, (0, 255, 255), 2)
        representative = detection or (max(visible, key=lambda item: item["confidence"]) if visible else None)
        return Observation(encode(annotated), representative, self.stable, detection)

    def close(self):
        if self.capture:
            self.capture.close()
        self.capture = None


class LatestMjpegCapture:
    """Drain camera continuously so slow inference consumes the latest complete JPEG."""
    def __init__(self, url: str):
        self.url = url
        self.condition = Condition()
        self.stop_event = Event()
        self.jpeg = None
        self.sequence = 0
        self.consumed = 0
        self.error = None
        self.thread = Thread(target=self._capture, name="rony-camera", daemon=True)
        self.thread.start()

    def _capture(self):
        buffer = b""
        try:
            with urlopen(self.url, timeout=2) as response:
                deadline = time.monotonic() + 3
                while not self.stop_event.is_set():
                    chunk = response.read1(65536)
                    if not chunk:
                        raise ConnectionError("La cámara cerró la conexión.")
                    buffer += chunk
                    if len(buffer) > 8 * 1024 * 1024:
                        raise ValueError("Frame de cámara demasiado grande.")
                    # Last complete JPEG in this chunk; old frames are intentionally discarded.
                    end = buffer.rfind(b"\xff\xd9")
                    start = buffer.rfind(b"\xff\xd8", 0, end) if end >= 0 else -1
                    if start >= 0:
                        jpeg, buffer = buffer[start:end + 2], buffer[end + 2:]
                        deadline = time.monotonic() + 3
                        with self.condition:
                            self.jpeg = jpeg
                            self.sequence += 1
                            self.condition.notify_all()
                    elif time.monotonic() >= deadline:
                        raise TimeoutError("La cámara no entregó un JPEG.")
        except Exception as error:
            with self.condition:
                self.error = error
                self.jpeg = None
                self.condition.notify_all()

    def read(self) -> bytes:
        with self.condition:
            ready = self.condition.wait_for(lambda: self.error or self.sequence > self.consumed or self.stop_event.is_set(), timeout=3)
            if self.error:
                raise self.error
            if not ready or self.stop_event.is_set() or self.jpeg is None:
                raise TimeoutError("No hay un nuevo frame de cámara.")
            self.consumed = self.sequence
            return self.jpeg

    def close(self):
        self.stop_event.set()
        with self.condition:
            self.condition.notify_all()
        self.thread.join(timeout=4)
