"""Preparation check. Reads robot calibration and validates plans; never sends actuator commands."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import sys
import time
from urllib.request import Request, urlopen
from urllib.error import HTTPError

ROOT = Path(__file__).resolve().parent
YOLO_ROOT = ROOT.parent / 'rony-yolo-isaac'


def agent_request(endpoint, token, path, payload=None):
    body = None if payload is None else json.dumps(payload, allow_nan=False).encode('utf-8')
    headers = {'Authorization': 'Bearer ' + token}
    if body is not None:
        headers['Content-Type'] = 'application/json'
    request = Request(endpoint.rstrip('/') + path, data=body, headers=headers)
    try:
        with urlopen(request, timeout=6) as response:
            return json.load(response)
    except HTTPError as error:
        try:
            detail = json.loads(error.read(4096)).get('detail', '')
            detail = ' '.join(detail.split())[:160] if isinstance(detail, str) else ''
        except (ValueError, AttributeError):
            detail = ''
        raise RuntimeError('Agente HTTP ' + str(error.code) + (': ' + detail if detail else '')) from None


def motion_preflight(endpoint, token, profile, profile_sha):
    from adapters.runtime_profile import robot_calibration
    packet = agent_request(endpoint, token, '/v1/calibration')
    if packet.get('schemaVersion') != 2 or packet.get('profileSha256') != profile_sha or packet.get('testMode'):
        raise RuntimeError('Actualiza nuestro agente; se requiere calibración leída del brazo real')
    if packet.get('motionPolicy') != 'vertical-home-before-disposal-v1':
        raise RuntimeError('Actualiza el agente para exigir elevación vertical y HOME antes de descargar')
    _, calibration_sha = robot_calibration(packet)
    values = profile['values']
    positions = [[values['HOME_' + axis] for axis in ('X', 'Y', 'Z')]]
    for destination in profile['bins'].values():
        for point in destination['transitMm']:
            if point not in positions:
                positions.append(point)
    result = agent_request(endpoint, token, '/v1/preflight', {
        'positions': positions, 'profileSha256': profile_sha, 'calibrationSha256': calibration_sha})
    if result.get('ok') is not True:
        raise RuntimeError('El agente no confirmó la validación previa')
    return {'calibrationSource': packet['source'], 'calibrationSha256': calibration_sha,
            'motionPolicy': packet['motionPolicy'], 'lateralTransportHeightMm': values['HOME_Z'],
            'positionsChecked': len(positions),
            'destinationsBaseDeg': {key: value['baseAngleDeg'] for key, value in profile['bins'].items()},
            'controllerValidation': result.get('positions'), 'motionCommandsSent': 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--robot', action='store_true')
    parser.add_argument('--host')
    args = parser.parse_args()
    checks = []

    def check(name, action):
        try:
            detail = action()
            checks.append({'check': name, 'ok': True, 'detail': detail})
            return True
        except Exception as error:
            checks.append({'check': name, 'ok': False, 'detail': str(error)[:200]})
            return False

    def python_version():
        if sys.version_info[:2] != (3, 11):
            raise RuntimeError('Usa .venv/Scripts/python.exe del proyecto, Python 3.11')
        return platform.python_version()

    def profile():
        path = YOLO_ROOT / 'runtime/export_notebook.py'
        spec = importlib.util.spec_from_file_location('export_notebook', path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        actual = json.loads((YOLO_ROOT / 'runtime/rony_profile.json').read_text(encoding='utf-8'))
        if actual != module.export():
            raise RuntimeError('El perfil difiere del notebook; revisa runtime/export_notebook.py')
        return '8 frames, pinza 80°, tres destinos provisionales'

    def inference():
        import numpy as np
        import torch
        from ultralytics import YOLO
        model = YOLO(str(YOLO_ROOT / 'modelo/yolo11n_300ep_20261028_211758_best.pt'))
        if model.names != {0: 'aluminum', 1: 'cardboard', 2: 'plastic'}:
            raise RuntimeError('Clases incompatibles')
        config = json.loads((YOLO_ROOT / 'runtime/rony_profile.json').read_text(encoding='utf-8'))['values']
        gpu = torch.cuda.is_available()
        model.predict(np.zeros((480, 640, 3), dtype=np.uint8), imgsz=config['IMGSZ'], conf=config['CONF'],
                      iou=config['IOU'], agnostic_nms=config['AGNOSTIC_NMS'], max_det=config['MAX_DET'],
                      quantize=16 if gpu else 32, device=0 if gpu else 'cpu', verbose=False)
        return {'torch': torch.__version__, 'device': torch.cuda.get_device_name(0) if gpu else 'cpu'}

    check('python', python_version)
    check('notebook_profile', profile)
    check('real_model_inference', inference)
    if args.robot:
        local_path = YOLO_ROOT / 'config.local.json'
        local = json.loads(local_path.read_text(encoding='utf-8-sig')) if local_path.is_file() else {}
        host = args.host or os.getenv('RONY_HOST') or local.get('rony_host') or '192.168.149.1'
        endpoint = os.getenv('RONY_ROBOT_AGENT_URL') or local.get('robot_agent_url') or 'http://' + host + ':8090'
        token = os.getenv('RONY_ROBOT_AGENT_TOKEN') or local.get('robot_agent_token', '')

        def robot_state():
            state = agent_request(endpoint, token, '/v1/state')
            if state.get('schemaVersion') != 2:
                raise RuntimeError('Actualiza nuestro agente para leer la calibración del brazo sin modificarla')
            if state.get('motionPolicy') != 'vertical-home-before-disposal-v1':
                raise RuntimeError('Actualiza el agente: falta protección de altura HOME en traslados')
            expected = hashlib.sha256((YOLO_ROOT / 'runtime/rony_profile.json').read_bytes().replace(b'\r\n', b'\n')).hexdigest()
            if state.get('profileSha256') != expected:
                raise RuntimeError('Perfil del agente desincronizado; vuelve a copiar el paquete')
            if state.get('testMode'):
                raise RuntimeError('Agente con robot falso; no corresponde al JetMax físico')
            if not state.get('connected'):
                raise RuntimeError('Agente disponible, pero sin estado ROS reciente')
            if not state.get('calibrationSha256'):
                raise RuntimeError('El brazo no entregó una calibración válida')
            return {'connected': True, 'encoderFeedback': False, 'pose': state.get('pose')}

        def camera():
            import cv2
            import numpy as np
            url = os.getenv('RONY_CAMERA_URL') or local.get('camera_url') or 'http://' + host + ':8080/stream?topic=/usb_cam/image_rect_color'
            buffer = b''
            with urlopen(url, timeout=3) as response:
                deadline = time.monotonic() + 5
                while time.monotonic() < deadline:
                    chunk = response.read1(65536)
                    if not chunk:
                        break
                    buffer += chunk
                    if len(buffer) > 4 * 1024 * 1024:
                        raise RuntimeError('Imagen demasiado grande')
                    start, end = buffer.find(b'\xff\xd8'), buffer.find(b'\xff\xd9')
                    if 0 <= start < end:
                        image = cv2.imdecode(np.frombuffer(buffer[start:end+2], np.uint8), cv2.IMREAD_COLOR)
                        if image is None or abs(image.shape[1] / image.shape[0] - 4/3) > .01:
                            raise RuntimeError('La cámara requiere imagen válida con encuadre 4:3')
                        return {'width': image.shape[1], 'height': image.shape[0]}
            raise RuntimeError('La cámara no entregó un JPEG')

        if check('agent_and_ros', robot_state):
            profile_data = json.loads((YOLO_ROOT / 'runtime/rony_profile.json').read_text(encoding='utf-8'))
            profile_sha = hashlib.sha256((YOLO_ROOT / 'runtime/rony_profile.json').read_bytes().replace(b'\r\n', b'\n')).hexdigest()
            check('robot_calibration_and_preflight', lambda: motion_preflight(endpoint, token, profile_data, profile_sha))
        check('camera', camera)
    print(json.dumps({'ok': all(c['ok'] for c in checks), 'checks': checks,
                      'motionCommandsSent': 0}, ensure_ascii=False, indent=2))
    return 0 if all(c['ok'] for c in checks) else 1


if __name__ == '__main__':
    raise SystemExit(main())
