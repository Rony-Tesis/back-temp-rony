"""Notebook inference/filters, robot-owned calibration; no actuator access."""
from pathlib import Path
from urllib.request import urlopen
from threading import Condition, Event, Thread
import math
import time

import cv2
import numpy as np
from runtime_profile import load_profile, robot_calibration
from worker_support import LABELS, Observation


def encode(frame):
    ok, encoded = cv2.imencode('.jpg', frame, [cv2.IMWRITE_JPEG_QUALITY, 80])
    if not ok:
        raise ValueError('No se pudo codificar el frame.')
    return encoded.tobytes()


def pixel_to_target(u, v, width, height, profile=None):
    profile = profile or load_profile()
    if not all(math.isfinite(n) for n in (u, v, width, height)) or width <= 0 or height <= 0:
        raise ValueError('Coordenadas inválidas.')
    if not 0 <= u < width or not 0 <= v < height or abs(width / height - 4 / 3) > .01:
        raise ValueError('Encuadre fuera de la calibración 4:3.')
    calibration, values = profile['calibration'], profile['values']
    if calibration is None:
        raise ValueError('Falta la calibración vigente del brazo.')
    k = np.asarray(calibration['K'], dtype=np.float64)
    r, _ = cv2.Rodrigues(np.asarray(calibration['R'], dtype=np.float64))
    inv_r = np.linalg.inv(r)
    trans = inv_r @ np.asarray(calibration['T'], dtype=np.float64).reshape(3)
    ray = inv_r @ np.linalg.inv(k) @ np.array([u * 640 / width, v * 480 / height, 1.])
    if abs(ray[2]) < 1e-9:
        raise ValueError('El rayo no intersecta el plano calibrado.')
    point = trans[2] / ray[2] * ray - trans
    x, y = values['HOME_X'] + float(point[0]), values['HOME_Y'] + float(point[1])
    if not math.isfinite(x + y) or abs(x) > 1000 or abs(y) > 1000:
        raise ValueError('Proyección fuera de rango.')
    return x, y


def describe_detection(category, confidence, u, v, width, height, profile=None):
    profile = profile or load_profile()
    try:
        x, y = pixel_to_target(u, v, width, height, profile)
        offset = {'x': x - profile['values']['HOME_X'], 'y': y - profile['values']['HOME_Y']}
        target = {'x': x, 'y': y}
    except ValueError:
        offset, target = None, None
    return {'residue': LABELS[category], 'shortName': LABELS[category], 'category': category,
            'confidence': confidence, 'pixel': {'x': u, 'y': v}, 'offsetMm': offset,
            'targetMm': target, 'destination': LABELS[category] + ' · destino provisional'}


class MiraVision:
    def __init__(self, camera_url, model_path, profile=None, detector=None):
        if detector is None and not model_path.is_file():
            raise ValueError('No existe el modelo configurado.')
        self.profile = profile if profile is not None else dict(load_profile(), calibration=None)
        if detector is None:
            from ultralytics import YOLO
            import torch
            self.model = YOLO(str(model_path))
            self.device = 0 if torch.cuda.is_available() else 'cpu'
        else:
            self.model, self.device = detector, 'tensorrt'
        if set(self.model.names.values()) != set(LABELS):
            raise ValueError('Las clases del modelo no coinciden con el notebook.')
        self.url, self.capture = camera_url, None
        self.calibration_sha = None
        self.deferred = []
        self.motion_active = False
        self.reset_tracking()

    def set_calibration(self, packet):
        calibration, sha = robot_calibration(packet) if packet is not None else (None, None)
        if sha != self.calibration_sha or self.profile['calibration'] != calibration:
            self.profile = dict(self.profile, calibration=calibration)
            self.calibration_sha = sha
            self.deferred = []
            self.reset_tracking()

    def reset_tracking(self):
        self.previous, self.stable = None, 0

    def defer_target(self, category, x, y):
        if category not in self.profile['enabledCategories'] or not all(math.isfinite(n) for n in (x, y)):
            raise ValueError('Residuo omitido inválido.')
        self.deferred = [item for item in self.deferred if not self._near_deferred(category, x, y, item)]
        self.deferred.append({'category': category, 'x': x, 'y': y, 'absent': 0})
        self.deferred = self.deferred[-32:]
        self.reset_tracking()

    def _near_deferred(self, category, x, y, item):
        return (category == item['category'] and math.hypot(x - item['x'], y - item['y'])
                < self.profile['values']['REPEAT_RADIUS_MM'])

    def _frame(self):
        if self.capture is None:
            self.capture = LatestMjpegCapture(self.url)
        jpeg = self.capture.read()
        frame = cv2.imdecode(np.frombuffer(jpeg, np.uint8), cv2.IMREAD_COLOR)
        if frame is None:
            raise ValueError('JPEG inválido.')
        return frame

    def read(self):
        p, v = self.profile, self.profile['values']
        try:
            frame = self._frame()
            result = self.model.predict(frame, imgsz=v['IMGSZ'], conf=v['CONF'], iou=v['IOU'],
                       agnostic_nms=v['AGNOSTIC_NMS'], max_det=v['MAX_DET'],
                       quantize=16 if self.device != 'cpu' else 32,
                       device=self.device, verbose=False)[0]
        except Exception:
            self.close()
            self.reset_tracking()
            raise
        candidates, visible, boxes = [], [], []
        height, width = frame.shape[:2]
        for box in result.boxes or []:
            category, confidence = result.names[int(box.cls[0])], float(box.conf[0])
            x1, y1, x2, y2 = map(float, box.xyxy[0])
            if (category not in LABELS or not all(math.isfinite(x) for x in (confidence, x1, y1, x2, y2))
                    or confidence < v['CONF'] or min(x2-x1, y2-y1) < v['MIN_BOX_SIDE_PX']
                    or (x2-x1)*(y2-y1)/(width*height) > v['MAX_BOX_AREA_RATIO']
                    or min(x1, y1) < v['EDGE_MARGIN_PX']
                    or x2 > width-v['EDGE_MARGIN_PX'] or y2 > height-v['EDGE_MARGIN_PX']):
                continue
            item = describe_detection(category, confidence, (x1+x2)/2, (y1+y2)/2, width, height, p)
            boxes.append((x1, y1, x2, y2, category, confidence))
            xy = item['targetMm']
            in_bin = xy is not None and any(math.hypot(xy['x']-b['positionMm'][0], xy['y']-b['positionMm'][1])
                      < v['DROP_EXCLUSION_MM'] for b in p['bins'].values())
            if in_bin:
                continue
            visible.append(item)
            workspace = p['workspaceMm']
            reachable = xy is not None and (workspace['x'][0] <= xy['x'] <= workspace['x'][1]
                         and workspace['y'][0] <= xy['y'] <= workspace['y'][1])
            if category in p['enabledCategories'] and confidence >= v['AUTO_MIN_CONF'] and reachable:
                candidates.append(item)
        if not self.motion_active:
            for item in self.deferred:
                seen = any(d['targetMm'] is not None and self._near_deferred(
                    d['category'], d['targetMm']['x'], d['targetMm']['y'], item) for d in visible)
                item['absent'] = 0 if seen else item['absent'] + 1
        self.deferred = [item for item in self.deferred if item['absent'] < v['EMPTY_CONFIRM_FRAMES']]
        candidates = [d for d in candidates if not any(self._near_deferred(
            d['category'], d['targetMm']['x'], d['targetMm']['y'], item) for item in self.deferred)]
        target = None
        if candidates:
            previous = self.previous
            same_class = [d for d in candidates if previous is not None and d['category'] == previous['category']]
            chosen = (min(same_class, key=lambda d: math.hypot(d['pixel']['x']-previous['pixel']['x'],
                       d['pixel']['y']-previous['pixel']['y'])) if same_class else
                       max(candidates, key=lambda d: d['confidence']))
            jump = math.inf if previous is None or chosen['category'] != previous['category'] else math.hypot(
                   chosen['pixel']['x']-previous['pixel']['x'], chosen['pixel']['y']-previous['pixel']['y'])
            self.stable = self.stable + 1 if jump <= v['MAX_PIXEL_JUMP'] else 1
            self.previous, target = chosen, chosen
        else:
            self.reset_tracking()
        annotated = frame.copy()
        for x1, y1, x2, y2, category, confidence in boxes:
            cv2.rectangle(annotated, (int(x1), int(y1)), (int(x2), int(y2)), (60, 220, 80), 2)
            label = {'plastic': 'plastico', 'cardboard': 'carton', 'aluminum': 'aluminio'}[category]
            cv2.putText(annotated, f'{label} {confidence:.2f}', (int(x1), max(20, int(y1)-7)),
                        cv2.FONT_HERSHEY_SIMPLEX, .55, (60, 220, 80), 2)
        cv2.putText(annotated, 'RONY / CAMARA REAL', (12, 28), cv2.FONT_HERSHEY_SIMPLEX, .6, (0, 255, 255), 2)
        representative = target or (max(visible, key=lambda d: d['confidence']) if visible else None)
        return Observation(encode(annotated), representative, self.stable, target, len(visible))

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
