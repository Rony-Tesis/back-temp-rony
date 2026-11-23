"""Continuous device-only WSS video. Latest frame only; never handles robot commands."""
import logging
from pathlib import Path
import ssl
import struct
import sys
import threading
import time
from urllib.parse import urlsplit, urlunsplit

LOG = logging.getLogger('rony.video')
MAX_IN_FLIGHT = 3
MAX_FRAME_AGE = .5
TARGET_FPS = 20


def render_preview(jpeg, boxes):
    # Preview-only compression. YOLO and calibration retain the original camera pixels.
    import cv2
    import numpy as np
    frame = cv2.imdecode(np.frombuffer(jpeg, np.uint8), cv2.IMREAD_COLOR)
    if frame is None:
        raise ValueError('JPEG de cámara inválido')
    scale = min(1., 640. / frame.shape[1])
    if scale < 1:
        frame = cv2.resize(frame, (640, int(frame.shape[0] * scale)), interpolation=cv2.INTER_AREA)
    for x1, y1, x2, y2, category, confidence in boxes:
        x1, y1, x2, y2 = [int(n * scale) for n in (x1, y1, x2, y2)]
        cv2.rectangle(frame, (x1, y1), (x2, y2), (60, 220, 80), 2)
        label = {'plastic': 'plastico', 'cardboard': 'carton', 'aluminum': 'aluminio'}[category]
        cv2.putText(frame, '{} {:.2f}'.format(label, confidence), (x1, max(18, y1 - 5)),
                    cv2.FONT_HERSHEY_SIMPLEX, .5, (60, 220, 80), 1)
    ok, encoded = cv2.imencode('.jpg', frame, [cv2.IMWRITE_JPEG_QUALITY, 65])
    if not ok or len(encoded) > 300000:
        raise ValueError('Imagen de video demasiado grande')
    return encoded.tobytes()


class FrameWindow:
    """A small acknowledged window bounds TCP backlog independently of camera or inference rate."""
    def __init__(self):
        self.condition = threading.Condition()
        self.pending = {}
        self.last_ack = 0.
        self.failed = False

    def reserve(self, sequence, stop):
        with self.condition:
            while len(self.pending) >= MAX_IN_FLIGHT and not stop.is_set() and not self.failed:
                if time.monotonic() - min(self.pending.values()) > 1:
                    raise TimeoutError('Video atrasado')
                self.condition.wait(.05)
            if self.failed or stop.is_set():
                raise ConnectionError('Video desconectado')
            self.pending[sequence] = time.monotonic()

    def acknowledge(self, sequence):
        with self.condition:
            if sequence not in self.pending:
                raise ValueError('Confirmación de video inválida')
            del self.pending[sequence]
            self.last_ack = time.monotonic()
            self.condition.notify_all()

    def fail(self):
        with self.condition:
            self.failed = True
            self.condition.notify_all()


class VideoClient:
    def __init__(self, api, token, boot_id, vision, control_healthy, allow_test_http=False):
        parsed = urlsplit(api)
        local_test = allow_test_http and parsed.scheme == 'http' and parsed.hostname in ('localhost', '127.0.0.1')
        if ((parsed.scheme != 'https' and not local_test) or not parsed.hostname
                or parsed.username or parsed.password or parsed.query or parsed.fragment
                or not token or not 32 <= len(token) <= 256):
            raise ValueError('El video requiere la API HTTPS y su credencial privada')
        self.url = urlunsplit(('ws' if local_test else 'wss', parsed.netloc,
                              parsed.path.rstrip('/') + '/api/v1/device/video', '', ''))
        self.token, self.boot_id, self.vision, self.control_healthy = token, boot_id, vision, control_healthy
        self.stop = threading.Event()
        self.window = FrameWindow()
        self.socket = None
        self.thread = threading.Thread(target=self.run, name='rony-video-wss', daemon=True)

    def healthy(self):
        return not self.window.failed and self.window.last_ack > 0 and time.monotonic() - self.window.last_ack < 2

    def _receive(self, connection, window):
        try:
            while not self.stop.is_set() and not window.failed:
                reply = connection.recv()
                if not isinstance(reply, str) or not reply.isdigit() or len(reply) > 20:
                    raise ValueError('Confirmación de video inválida')
                first = window.last_ack == 0
                window.acknowledge(int(reply))
                if first:
                    LOG.info('Video WSS confirmado; objetivo %s FPS, cola máxima %s imágenes', TARGET_FPS, MAX_IN_FLIGHT)
        except Exception:
            window.fail()

    def _connect(self):
        # Bundled pure-Python dependency is isolated from ROS/system Python and hash checked at packaging.
        for wheel in (Path(__file__).parent / 'vendor').glob('websocket_client-*.whl'):
            if str(wheel) not in sys.path:
                sys.path.insert(0, str(wheel))
        import websocket
        context = ssl.create_default_context()
        context.options |= ssl.OP_NO_TLSv1 | ssl.OP_NO_TLSv1_1
        connection = websocket.create_connection(self.url, timeout=2, enable_multithread=True,
            suppress_origin=True, redirect_limit=0,
            header={'Authorization': 'Bearer ' + self.token, 'X-Rony-Boot-Id': self.boot_id},
            sslopt={'context': context, 'cert_reqs': ssl.CERT_REQUIRED, 'check_hostname': True})
        if connection.getstatus() != 101:
            connection.shutdown()
            raise ConnectionError('La API no aceptó el canal de video')
        return connection

    def _stream(self, connection):
        window = self.window = FrameWindow()
        reader = threading.Thread(target=self._receive, args=(connection, window), name='rony-video-acks', daemon=True)
        reader.start()
        after, next_frame = 0, 0.
        try:
            while not self.stop.is_set() and self.control_healthy():
                self.stop.wait(max(0, next_frame - time.monotonic()))
                if self.stop.is_set(): break
                started = time.monotonic()
                # Wait for capacity before reading the camera: a slow network never leaves queued JPEGs.
                window.reserve(after + 1, self.stop)
                reserved = after + 1
                try:
                    sequence, jpeg, captured_at, boxes = self.vision.preview(after)
                except TimeoutError:
                    with window.condition:
                        window.pending.pop(reserved, None)
                    self.stop.wait(.025)
                    continue
                if time.monotonic() - captured_at > MAX_FRAME_AGE:
                    raise TimeoutError('Frame de cámara atrasado')
                encoded = render_preview(jpeg, boxes)
                # Source may skip hundreds of camera frames. Ack the actual sequence, not the reservation.
                with window.condition:
                    del window.pending[reserved]
                    window.pending[sequence] = time.monotonic()
                after = sequence
                connection.send_binary(struct.pack('!Q', sequence) + encoded)
                next_frame = started + 1. / TARGET_FPS
        finally:
            window.fail()
            connection.shutdown()
            reader.join(timeout=3)

    def run(self):
        backoff = 1.
        while not self.stop.is_set():
            if not self.control_healthy():
                self.stop.wait(.2)
                continue
            try:
                self.socket = self._connect()
                self._stream(self.socket)
                backoff = 1.
            except Exception as error:
                # Never log headers, credentials, camera bytes or command content.
                LOG.warning('Video continuo no disponible (%s); se conserva la captura HTTPS', type(error).__name__)
            finally:
                self.window.fail()
                if self.socket:
                    self.socket.shutdown()
                    self.socket = None
            self.stop.wait(backoff)
            backoff = min(8., backoff * 2)

    def close(self):
        self.stop.set()
        self.window.fail()
        if self.socket: self.socket.shutdown()
        self.thread.join(timeout=5)
