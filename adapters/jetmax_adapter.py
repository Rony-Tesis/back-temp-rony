"""ROS adapter with pinned host keys and continuous controller telemetry."""
import ast
import json
import math
import os
from pathlib import Path
import shlex
from threading import Condition, Thread
import time

from worker_support import HOME, validate_position

CALIBRATION = {
    'K': [[421.7179870605469, 0., 331.8549044912379], [0., 464.7308349609375, 234.46281488904788], [0., 0., 1.]],
    'R': [[-.07697136252374011], [3.086054291330642], [.1219708621340273]],
    'T': [[-16.652967984876717], [51.4273611584664], [183.827498153882]],
}


def credentials(notebook=None):
    values = {}
    # Read only literal assignments. Never execute the notebook or log credentials.
    if notebook:
        data = json.loads(Path(notebook).read_text(encoding='utf-8'))
        for cell in data['cells']:
            if cell['cell_type'] != 'code':
                continue
            try:
                tree = ast.parse(''.join(cell['source']))
            except SyntaxError:
                continue
            for node in tree.body:
                if isinstance(node, ast.Assign) and isinstance(node.value, ast.Constant):
                    for target in node.targets:
                        if isinstance(target, ast.Name) and target.id in ('RONY_HOST', 'RONY_USER', 'RONY_PASSWORD', 'RONY_PASS'):
                            values[target.id] = node.value.value
    host = os.getenv('RONY_ROBOT_HOST', values.get('RONY_HOST', '192.168.149.1'))
    user = os.getenv('RONY_ROBOT_USER', values.get('RONY_USER', 'hiwonder'))
    password = os.getenv('RONY_ROBOT_PASSWORD', values.get('RONY_PASSWORD', values.get('RONY_PASS')))
    if not password:
        raise ValueError('Configura las credenciales SSH del JetMax en el entorno o notebook local.')
    return host, user, password


class JetMaxRobot:
    def __init__(self, notebook=None):
        import paramiko
        self.condition = Condition()
        self.pose = None
        self.pose_at = 0.
        self.closed = False
        self.responses = {}
        self.sequence = 0
        self.on_pose = lambda pose: None
        self.client = paramiko.SSHClient()
        self.client.load_host_keys(str(Path.home() / '.ssh' / 'known_hosts'))
        self.client.set_missing_host_key_policy(paramiko.RejectPolicy())
        host, user, password = credentials(notebook)
        try:
            self.client.connect(host, username=user, password=password, timeout=8, banner_timeout=8,
                                auth_timeout=8, look_for_keys=False, allow_agent=False)
            bridge = Path(__file__).with_name('jetmax_bridge.py').read_text(encoding='utf-8')
            command = 'bash -ic ' + shlex.quote('python3 -u -c ' + shlex.quote(bridge))
            self.stdin, self.stdout, self.stderr = self.client.exec_command(command, timeout=4)
            self.stdout.channel.settimeout(None)
            self.reader = Thread(target=self._read, name='rony-ros-status', daemon=True)
            self.reader.start()
            with self.condition:
                if not self.condition.wait_for(lambda: self.pose is not None or self.closed, timeout=8) or self.closed:
                    raise ValueError('No hay telemetría ROS; revisa SSH y que solo una instancia controle el brazo.')
            self.preflight([HOME])
        except Exception:
            self.client.close()
            raise

    def _read(self):
        try:
            for line in self.stdout:
                if len(line) > 8192 or not line.startswith('{'):
                    continue
                item = json.loads(line)
                if item.get('kind') == 'telemetry':
                    pose = item['pose']
                    if not all(isinstance(pose[key], (int, float)) and math.isfinite(pose[key]) for key in ('x', 'y', 'z', 'gripperAngle')):
                        continue
                    with self.condition:
                        self.pose, self.pose_at = pose, time.monotonic()
                        self.condition.notify_all()
                    self.on_pose(dict(pose))
                elif item.get('kind') == 'reply':
                    with self.condition:
                        self.responses[item['id']] = (item['ok'], item.get('reason', 'Controller unavailable'))
                        self.condition.notify_all()
        except Exception:
            pass
        finally:
            with self.condition:
                self.closed = True
                self.condition.notify_all()

    def current_pose(self):
        with self.condition:
            if self.closed or self.pose is None or time.monotonic() - self.pose_at > 2:
                raise ValueError('Se perdió la telemetría ROS.')
            return dict(self.pose)

    def _request(self, action, **values):
        with self.condition:
            self.sequence += 1
            identity = self.sequence
            payload = json.dumps({'id': identity, 'action': action, **values}, allow_nan=False)
            self.stdin.write(payload + '\n')
            self.stdin.flush()
            if not self.condition.wait_for(lambda: identity in self.responses or self.closed, timeout=4):
                raise ValueError('El controlador ROS no respondió.')
            ok, reason = self.responses.pop(identity, (False, 'Controller unavailable'))
            if not ok:
                raise ValueError('Validación ROS: ' + reason)

    def preflight(self, positions):
        for position in positions:
            validate_position(position)
        self._request('preflight', positions=positions, calibration=CALIBRATION)

    def execute(self, step, expected, cancelled):
        self.current_pose()
        if cancelled.is_set():
            raise InterruptedError('Ciclo cancelado antes del siguiente tramo.')
        if step.position:
            self._request('move', position=step.position, duration=step.duration, expected=expected)
            wanted = dict(zip(('x', 'y', 'z'), step.position))
            wanted['gripperAngle'] = expected['gripperAngle']
        else:
            self._request('gripper', angle=step.gripper, duration=step.duration, expected=expected)
            wanted = {**expected, 'gripperAngle': step.gripper}
        # ROS exposes controller setpoints, not encoder-confirmed arrival. Wait out the physical
        # duration and require fresh, matching telemetry before allowing the next segment.
        deadline = time.monotonic() + step.duration + .35
        while time.monotonic() < deadline:
            self.current_pose()
            time.sleep(.05)
        actual = self.current_pose()
        if any(abs(actual[key] - wanted[key]) > 2 for key in wanted):
            raise ValueError('La posición reportada no coincide con el comando; ciclo detenido.')
        if cancelled.is_set():
            raise InterruptedError('Cancelado al terminar el tramo actual.')
        return actual

    def close(self):
        self.closed = True
        self.client.close()
