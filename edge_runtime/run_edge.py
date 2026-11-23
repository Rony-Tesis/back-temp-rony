"""JetMax autonomous runtime: native YOLO, local planning/ROS, outbound HTTPS only."""
import argparse
import base64
import http.client
import json
import logging
import os
from pathlib import Path
import queue
import signal
import ssl
import sys
import threading
import time
from urllib.parse import urlparse
from urllib.error import HTTPError
from urllib.request import HTTPRedirectHandler, HTTPSHandler, Request, build_opener

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / 'robot_agent' if (ROOT / 'robot_agent').exists() else ROOT.parent / 'robot_agent'))
sys.path.insert(0, str(ROOT / 'adapters' if (ROOT / 'adapters').exists() else ROOT.parent / 'adapters'))
from agent import Executor, Profile, RosDriver
from controller import Controller
from outbox import Outbox
from video import VideoClient

LOG = logging.getLogger('rony.edge')


class Vision:
    def __init__(self, driver, profile, model_directory, camera_url):
        self.driver, self.profile, self.model_directory, self.camera_url = driver, profile, model_directory, camera_url
        self.lock, self.stop_event, self.actions = threading.RLock(), threading.Event(), queue.Queue()
        self.sequence, self.observation, self.at, self.calibration, self.fps = 0, None, 0., None, 0.
        self.capture, self.overlay = None, []
        self.overlay_at, self.motion_active = 0., False
        self.thread = threading.Thread(target=self.run, name='rony-local-yolo', daemon=True)

    def latest(self):
        with self.lock:
            return self.sequence, self.observation, self.at, self.calibration

    def reset_tracking(self): self.actions.put(('reset', None))
    def defer_target(self, target): self.actions.put(('defer', target))
    def set_motion_active(self, active): self.actions.put(('motion', active))

    def preview(self, after):
        with self.lock:
            capture = self.capture
            boxes = self.overlay if not self.motion_active and time.monotonic() - self.overlay_at < .3 else []
        if capture is None:
            raise TimeoutError('Cámara iniciándose')
        sequence, jpeg, captured_at = capture.latest(after, timeout=.25)
        return sequence, jpeg, captured_at, boxes

    def run(self):
        from mira_vision import MiraVision, LatestMjpegCapture
        from yolo_tensorrt import TensorRtYolo
        detector, source = None, None
        try:
            detector = TensorRtYolo(self.model_directory)
            with self.lock:
                self.capture = LatestMjpegCapture(self.camera_url)
            source = MiraVision(self.camera_url, Path(self.model_directory) / 'yolo11n.engine',
                                dict(self.profile.data, calibration=None), detector=detector, capture=self.capture)
            while not self.stop_event.is_set():
                frame_started = time.monotonic()
                try:
                    while not self.actions.empty():
                        action, payload = self.actions.get_nowait()
                        if action == 'reset': source.reset_tracking()
                        elif action == 'motion':
                            source.motion_active = self.motion_active = payload
                        elif action == 'defer': source.defer_target(payload['category'], payload['targetMm']['x'], payload['targetMm']['y'])
                    source.set_calibration(self.driver.calibration())
                    observation = source.read()
                    now = time.monotonic()
                    with self.lock:
                        self.fps = min(240., 1. / max(.001, now - self.at)) if self.at else 0.
                        self.sequence, self.observation, self.at = self.sequence + 1, observation, now
                        self.calibration = source.calibration_sha
                        self.overlay, self.overlay_at = list(source.preview_boxes), now
                    self.stop_event.wait(max(.0, self.profile.values['DETECTION_PERIOD'] - (now - frame_started)))
                except Exception:
                    with self.lock:
                        self.observation, self.at, self.fps = None, 0., 0.
                    LOG.exception('Fallo de cámara o inferencia local')
                    source.close()
                    self.stop_event.wait(2)
        except Exception:
            LOG.exception('No se pudo iniciar YOLO dentro del JetMax')
        finally:
            if source: source.close()
            if self.capture: self.capture.close()
            if detector: detector.close()

    def close(self):
        self.stop_event.set()
        self.thread.join(timeout=8)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, url):
        return None


class CloudClient:
    def __init__(self, api_url, token, device_id, controller, allow_test_http=False):
        parsed = urlparse(api_url)
        if (parsed.scheme != 'https' and not (allow_test_http and parsed.scheme == 'http' and parsed.hostname in ('127.0.0.1', 'localhost'))):
            raise ValueError('La API del despliegue requiere HTTPS')
        if parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError('La URL de la API no debe contener credenciales ni parámetros')
        if not token or not 32 <= len(token) <= 256:
            raise ValueError('Configura RONY_DEVICE_TOKEN')
        self.endpoint, self.token, self.device_id, self.controller = api_url.rstrip('/') + '/api/v1/device/exchange', token, device_id, controller
        context = ssl.create_default_context()
        context.options |= ssl.OP_NO_TLSv1 | ssl.OP_NO_TLSv1_1
        self.opener = build_opener(NoRedirect(), HTTPSHandler(context=context))
        self.connection = (http.client.HTTPSConnection(parsed.hostname, parsed.port, timeout=5, context=context)
                           if parsed.scheme == 'https' else http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=5))
        self.path = parsed.path.rstrip('/') + '/api/v1/device/exchange'
        self.poll_seconds = .2
        self.last_response, self.sequence, self.acknowledged = 0., 0, []
        self.video = None
        self.last_snapshot = 0.
        self.stop_event = threading.Event()
        self.thread = threading.Thread(target=self.run, name='rony-cloud-https', daemon=True)

    def healthy(self): return self.last_response > 0 and time.monotonic() - self.last_response < 15

    def exchange_once(self):
        self.sequence += 1
        packet = self.controller.packet(self.device_id, self.sequence)
        _, observation, at, _ = self.controller.vision.latest()
        video_healthy = self.video is not None and self.video.healthy()
        if (not video_healthy and time.monotonic() - self.last_snapshot >= .2
                and observation and time.monotonic() - at < 5 and len(observation.jpeg) <= 300000):
            packet['jpeg'] = base64.b64encode(observation.jpeg).decode('ascii')
            self.last_snapshot = time.monotonic()
        packet['acknowledgedCommands'] = self.acknowledged[-32:]
        body = json.dumps(packet, ensure_ascii=False, allow_nan=False).encode('utf-8')
        self.connection.request('POST', self.path, body=body, headers={'Authorization': 'Bearer ' + self.token,
            'Content-Type': 'application/json', 'X-Rony-Control': '1'})
        with self.connection.getresponse() as response:
            raw = response.read(65537)
            if response.status != 200:
                raise ValueError('API HTTPS respondió HTTP ' + str(response.status))
        if len(raw) > 65536:
            raise ValueError('Respuesta de la API demasiado grande')
        reply = json.loads(raw.decode('utf-8'))
        if reply.get('controlLeaseSeconds') != 15 or len(reply.get('commands', [])) > 16:
            raise ValueError('Protocolo HTTPS incompatible')
        interval = reply.get('pollIntervalMs', 1000)
        if not isinstance(interval, (int, float)) or not 100 <= interval <= 5000:
            raise ValueError('Intervalo de telemetría inválido')
        self.poll_seconds = interval / 1000.
        self.last_response = time.monotonic()
        self.controller.outbox.acknowledge(reply.get('acknowledgedOperations', []))
        self.acknowledged = []
        for command in reply.get('commands', []):
            self.controller.enqueue(command)
            self.acknowledged.append(command['id'])

    def run(self):
        while not self.stop_event.is_set():
            started, delay = time.monotonic(), self.poll_seconds
            try:
                self.exchange_once()
                delay = self.poll_seconds
            except Exception as error:
                self.connection.close()
                delay = 1.
                if isinstance(getattr(error, 'reason', error), ssl.SSLError):
                    LOG.warning('No se pudo verificar TLS: revisa fecha/hora del JetMax y certificado de la API')
                else:
                    LOG.warning('API HTTPS no disponible: %s', type(error).__name__)
            # Account for upload time instead of adding another full second after every frame.
            self.stop_event.wait(max(.01, delay - (time.monotonic() - started)))

    def close(self):
        self.stop_event.set()
        self.thread.join(timeout=7)
        self.connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', required=True)
    parser.add_argument('--model-dir', required=True)
    parser.add_argument('--state-dir', default='/var/lib/rony-edge')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO)
    profile = Profile(args.profile)
    if args.check:
        from yolo_tensorrt import TensorRtYolo
        from mira_vision import MiraVision
        detector = TensorRtYolo(args.model_dir)
        source = None
        try:
            driver = RosDriver(profile, node_name='rony_edge_read_only_check')
            driver.preflight(profile.transit)
            source = MiraVision('http://127.0.0.1:8080/stream?topic=/usb_cam/image_rect_color',
                                Path(args.model_dir) / 'yolo11n.engine',
                                dict(profile.data, calibration=None), detector=detector)
            source.set_calibration(driver.calibration())
            observation = source.read()
            tls_verified = None
            environment_file = ROOT / 'edge.env'
            if environment_file.is_file():
                values = dict(line.split('=', 1) for line in environment_file.read_text().splitlines() if line)
                probe = CloudClient(values['RONY_API_URL'], values['RONY_DEVICE_TOKEN'], 'jetmax-01', None)
                request = Request(probe.endpoint, headers={'Authorization': 'Bearer ' + values['RONY_DEVICE_TOKEN']})
                try:
                    probe.opener.open(request, timeout=8).close()
                    raise RuntimeError('Respuesta inesperada al comprobar la API')
                except HTTPError as error:
                    # GET is deliberately unsupported: 405 proves TLS + device authentication,
                    # without registering a robot, uploading telemetry or consuming commands.
                    if error.code != 405:
                        raise RuntimeError('La API no confirmó la credencial del dispositivo: HTTP ' + str(error.code))
                    tls_verified = True
            print(json.dumps({'ok': True, 'calibrationSha256': driver.calibration()['sha256'],
                              'modelLoadsOnJetson': True, 'nativeVisionPipelineReady': True,
                              'apiTlsAndDeviceCredentialVerified': tls_verified,
                              'visibleObjects': observation.visible_count, 'localYoloDetection': observation.detection,
                              'motionCommandsSent': 0, 'rosParametersWritten': 0}, indent=2))
        finally:
            if source: source.close()
            detector.close()
        return
    token, api = os.environ.get('RONY_DEVICE_TOKEN', ''), os.environ.get('RONY_API_URL', '')
    import fcntl
    lock_file = open('/tmp/rony-physical.lock', 'a')
    os.chmod('/tmp/rony-physical.lock', 0o600)
    fcntl.flock(lock_file, fcntl.LOCK_EX | fcntl.LOCK_NB)
    driver = RosDriver(profile)
    executor = Executor(driver, profile)
    outbox = Outbox(Path(args.state_dir) / 'operations.sqlite3')
    vision = Vision(driver, profile, args.model_dir,
                    os.environ.get('RONY_CAMERA_URL', 'http://127.0.0.1:8080/stream?topic=/usb_cam/image_rect_color'))
    client_ref = []
    controller = Controller(executor, vision, outbox, lambda: bool(client_ref and client_ref[0].healthy()))
    client = CloudClient(api, token, os.environ.get('RONY_DEVICE_ID', 'jetmax-01'), controller)
    video = VideoClient(api, token, controller.boot_id, vision, client.healthy)
    client.video = video
    client_ref.append(client)
    stop = threading.Event()
    for signum in (signal.SIGINT, signal.SIGTERM):
        signal.signal(signum, lambda *_: stop.set())
    try:
        vision.thread.start()
        client.thread.start()
        video.thread.start()
        LOG.info('YOLO y ciclo en JetMax; conexión saliente HTTPS; arranque sin movimiento')
        while not stop.wait(.1):
            controller.tick()
    finally:
        executor.close()
        video.close()
        client.close()
        vision.close()
        outbox.close()
        lock_file.close()


if __name__ == '__main__':
    main()
