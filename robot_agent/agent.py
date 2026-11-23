"""JetMax ROS 1 executor. Python 3.6+, standard library only outside the vendor ROS SDK.

The entire submitted trajectory runs on the robot. Startup never moves. A lost
owner lease/cancel finishes the issued segment and prevents the next command.
"""
import argparse
import collections
import hashlib
import hmac
import json
import logging
import math
import os
from pathlib import Path
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn

LOG = logging.getLogger('rony.agent')
MOTION_POLICY = 'vertical-home-before-disposal-v1'
STORAGE_POSITION = (-42.6878, -27.0905, 225.0)


def near_point(left, right, tolerance=2):
    return all(abs(a - b) <= tolerance for a, b in zip(left, right))


def finite(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def calibration_packet(parameters):
    """Normalize a read-only ROS snapshot. Never write camera or ROS parameters."""
    if not isinstance(parameters, dict):
        raise ValueError('Calibración del brazo no disponible')
    result = {}
    for key, rows, columns in (('K', 3, 3), ('R', 3, 1), ('T', 3, 1)):
        value = parameters.get(key)
        if columns == 1 and isinstance(value, (list, tuple)) and len(value) == rows and all(finite(n) for n in value):
            value = [[n] for n in value]
        if (not isinstance(value, (list, tuple)) or len(value) != rows
                or any(not isinstance(row, (list, tuple)) or len(row) != columns
                       or not all(finite(n) for n in row) for row in value)):
            raise ValueError('Calibración del brazo inválida: ' + key)
        result[key] = [[float(n) for n in row] for row in value]
    k = result['K']
    determinant = (k[0][0] * (k[1][1] * k[2][2] - k[1][2] * k[2][1])
                   - k[0][1] * (k[1][0] * k[2][2] - k[1][2] * k[2][0])
                   + k[0][2] * (k[1][0] * k[2][1] - k[1][1] * k[2][0]))
    if k[0][0] <= 0 or k[1][1] <= 0 or not math.isfinite(determinant) or abs(determinant) < 1e-9:
        raise ValueError('Matriz K del brazo no invertible')
    raw = json.dumps(result, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')
    return {'source': 'ros:/camera_cal/card_params', 'sha256': hashlib.sha256(raw).hexdigest(),
            'parameters': result}


class Profile:
    def __init__(self, path):
        raw = Path(path).read_bytes()
        self.sha256 = hashlib.sha256(raw.replace(b'\r\n', b'\n')).hexdigest()
        data = json.loads(raw.decode('utf-8'))
        self.data = data
        if data.get('schemaVersion') != 1:
            raise ValueError('Perfil incompatible')
        self.values = data['values']
        self.calibration = data['calibration']
        self.workspace = data['workspaceMm']
        self.base_limits = data['motion']['baseJointLimitsDeg']
        self.transit = [[self.values['HOME_' + axis] for axis in ('X', 'Y', 'Z')]]
        self.transit += [point for destination in data['bins'].values() for point in destination['transitMm']]

    def position(self, point):
        if not isinstance(point, (list, tuple)) or len(point) != 3 or not all(finite(v) for v in point):
            raise ValueError('Posición inválida')
        in_pickup = all(self.workspace[axis][0] <= value <= self.workspace[axis][1]
                        for axis, value in zip(('x', 'y', 'z'), point))
        in_transit = any(all(abs(a - b) <= .01 for a, b in zip(point, allowed)) for allowed in self.transit)
        if not (in_pickup or in_transit or near_point(point, STORAGE_POSITION, .0001)):
            raise ValueError('Objetivo fuera de recogida o del recorrido de descarga autorizado')
        return tuple(point)

    def steps(self, steps, manual_action=None):
        if not isinstance(steps, list) or not 1 <= len(steps) <= 16:
            raise ValueError('Trayectoria inválida')
        result = []
        for step in steps:
            if not isinstance(step, dict) or set(step) - {'stage', 'label', 'duration', 'position', 'gripper'}:
                raise ValueError('Tramo inválido')
            stage, duration = step.get('stage'), step.get('duration')
            label = step.get('label')
            if (not isinstance(stage, int) or isinstance(stage, bool) or not 0 <= stage <= 5
                    or not finite(duration) or not 1 <= duration <= 3
                    or not isinstance(label, str) or not 1 <= len(label) <= 200):
                raise ValueError('Tramo inválido')
            if ('position' in step) == ('gripper' in step):
                raise ValueError('El tramo requiere posición o pinza')
            if 'position' in step:
                self.position(step['position'])
                if near_point(step['position'], STORAGE_POSITION, .0001) and manual_action != 'park':
                    raise ValueError('La posición de guardado requiere el control manual park')
            elif (not finite(step['gripper']) or step['gripper'] not in
                  ((90,) if manual_action == 'close-gripper' else
                   (self.values['GRIPPER_OPEN'], self.values['GRIPPER_PICK']))):
                raise ValueError('Ángulo de pinza inválido')
            result.append(dict(step))
        return result

    def transition(self, current, target):
        tolerance = self.values['POSITION_TOLERANCE']
        home = [self.values['HOME_' + axis] for axis in ('X', 'Y', 'Z')]
        parking_pair = ((near_point(current, home, tolerance) and near_point(target, STORAGE_POSITION, .0001))
                        or (near_point(current, STORAGE_POSITION, tolerance) and near_point(target, home, tolerance)))
        if parking_pair:
            return
        lateral = any(abs(target[i] - current[i]) > tolerance for i in (0, 1))
        if lateral and (min(current[2], target[2]) < self.values['HOME_Z'] - tolerance
                        or abs(current[2] - target[2]) > tolerance):
            raise ValueError('Traslado lateral bloqueado: primero eleva verticalmente a altura HOME')

    def manual_steps(self, action, expected):
        if action == 'close-gripper':
            return [{'stage': 0, 'label': 'Cerrando pinza manualmente a 90°', 'duration': 1.0, 'gripper': 90}]
        if action == 'park':
            origin = [expected[key] for key in ('x', 'y', 'z')]
            if not near_point(origin, [self.values['HOME_' + axis] for axis in ('X', 'Y', 'Z')]):
                raise RuntimeError('Vuelve a HOME con Resetear antes de guardar el brazo')
            return [{'stage': 0, 'label': 'Moviendo a posición de guardado', 'duration': 3.0,
                     'position': list(STORAGE_POSITION)}]
        raise ValueError('Control manual desconocido')

    def trajectory(self, steps, expected):
        current = [expected[key] for key in ('x', 'y', 'z')]
        home = [self.values['HOME_' + axis] for axis in ('X', 'Y', 'Z')]
        tolerance = self.values['POSITION_TOLERANCE']
        home_seen = all(abs(a - b) <= tolerance for a, b in zip(current, home))
        holding = expected['gripperAngle'] > self.values['GRIPPER_POSITION_TOLERANCE']
        for step in steps:
            if 'gripper' in step:
                holding = step['gripper'] != self.values['GRIPPER_OPEN']
                if holding:
                    home_seen = False
                continue
            target = step['position']
            self.transition(current, target)
            at_home = all(abs(a - b) <= tolerance for a, b in zip(target, home))
            lateral = any(abs(target[i] - current[i]) > tolerance for i in (0, 1))
            in_transit = any(all(abs(a - b) <= .01 for a, b in zip(target, point)) for point in self.transit)
            if holding and step['stage'] == 4 and lateral and in_transit and not at_home and not home_seen:
                raise ValueError('Descarga bloqueada: vuelve a HOME antes de girar con el objeto')
            home_seen = home_seen or at_home
            current = target


class RosDriver:
    def __init__(self, profile, node_name='rony_agent'):
        import rospy
        import rostopic
        import justkinematics
        from jetmax_control.msg import SetJetMax, SetServo
        self.profile, self.rospy, self.kinematics = profile, rospy, justkinematics
        self.SetJetMax, self.SetServo = SetJetMax, SetServo
        self.lock = threading.Lock()
        self.pose, self.received = None, 0.0
        rospy.init_node(node_name, anonymous=False, disable_signals=True)
        status_type, _, _ = rostopic.get_topic_class('/jetmax/status', blocking=False)
        if status_type is None:
            raise RuntimeError('No existe /jetmax/status; inicia jetmax_control')
        self.subscriber = rospy.Subscriber('/jetmax/status', status_type, self.observe, queue_size=1)
        self.arm = rospy.Publisher('/jetmax/command', SetJetMax, queue_size=1, latch=False)
        self.gripper = rospy.Publisher('/jetmax/end_effector/servo1/command', SetServo, queue_size=1, latch=False)
        deadline = time.monotonic() + 10
        while self.pose is None and time.monotonic() < deadline:
            time.sleep(.05)
        self.current_pose()

    def observe(self, message):
        pose = {key: float(getattr(message, key)) for key in ('x', 'y', 'z')}
        pose['gripperAngle'] = float(message.pwm1)
        if not all(finite(value) for value in pose.values()):
            return
        joints = {key: getattr(message, key, None) for key in ('joint1', 'joint2', 'joint3')}
        if all(finite(value) for value in joints.values()):
            pose['joints'] = joints
        with self.lock:
            self.pose, self.received = pose, time.monotonic()

    def current_pose(self):
        with self.lock:
            if self.pose is None or time.monotonic() - self.received >= .8:
                raise RuntimeError('Telemetría ROS desactualizada')
            return dict(self.pose)

    def preflight(self, positions):
        self.current_pose()
        self.calibration()
        deadline = time.monotonic() + 2
        while not (self.arm.get_num_connections() and self.gripper.get_num_connections()):
            if time.monotonic() >= deadline:
                raise RuntimeError('Controlador ROS sin suscriptores')
            time.sleep(.05)
        checked = []
        for position in positions:
            point = self.profile.position(position)
            angles = self.kinematics.inverse(16.8, point)
            pulses = self.kinematics.deg_to_pulse(angles)
            if not self.profile.base_limits[0] - .001 <= angles[0] <= self.profile.base_limits[1] + .001:
                raise ValueError('Giro de base fuera del margen permitido: 10° a 230°')
            if (len(pulses) != 3 or not all(finite(v) and 0 <= v <= 1000 for v in pulses)
                    or pulses[1] > 700 or pulses[2] < 470):
                raise ValueError('Destino no alcanzable sin limitar servos')
            checked.append({'positionMm': list(point), 'baseAngleDeg': float(angles[0]),
                            'servoPulses': [float(pulse) for pulse in pulses]})
        return checked

    def calibration(self):
        return calibration_packet(self.rospy.get_param('/camera_cal/card_params'))

    def execute(self, step, expected):
        actual = self.current_pose()
        if any(abs(actual[key] - expected[key]) > self.profile.values['POSITION_TOLERANCE']
               for key in ('x', 'y', 'z')) or abs(actual['gripperAngle'] - expected['gripperAngle']) > 3:
            raise RuntimeError('Movimiento externo o posición inicial inesperada')
        if 'position' in step:
            self.profile.transition([actual[key] for key in ('x', 'y', 'z')], step['position'])
            message = self.SetJetMax()
            message.x, message.y, message.z = step['position']
            message.duration = step['duration']
            if not self.arm.get_num_connections():
                raise RuntimeError('Se perdió jetmax_control')
            self.arm.publish(message)
            wanted = dict(zip(('x', 'y', 'z'), step['position']))
            wanted['gripperAngle'] = expected['gripperAngle']
        else:
            message = self.SetServo()
            message.data, message.duration = step['gripper'], step['duration']
            if not self.gripper.get_num_connections():
                raise RuntimeError('Se perdió el controlador de pinza')
            self.gripper.publish(message)
            wanted = {key: expected[key] for key in ('x', 'y', 'z', 'gripperAngle')}
            wanted['gripperAngle'] = step['gripper']
        # ROS status reports setpoints, so a matching value never skips the motion timer.
        deadline = time.monotonic() + step['duration']
        while time.monotonic() < deadline:
            self.current_pose()
            time.sleep(.02)
        stable_since = None
        deadline = time.monotonic() + self.profile.values['GRIPPER_POSITION_TIMEOUT' if 'gripper' in step else 'POSITION_TIMEOUT'] + self.profile.values['POSITION_STABLE_TIME']
        while True:
            pose = self.current_pose()
            matches = all(abs(pose[k] - v) <= (self.profile.values['GRIPPER_POSITION_TOLERANCE']
                          if k == 'gripperAngle' else self.profile.values['POSITION_TOLERANCE'])
                          for k, v in wanted.items())
            if matches:
                if stable_since is None:
                    stable_since = time.monotonic()
                if time.monotonic() - stable_since >= self.profile.values['POSITION_STABLE_TIME']:
                    break
            else:
                stable_since = None
            if time.monotonic() >= deadline:
                raise RuntimeError('No se confirmó el final del tramo')
            time.sleep(.05)
        settle = (self.profile.values['GRIPPER_CLOSE_SETTLE']
                  if step.get('gripper') == self.profile.values['GRIPPER_PICK']
                  else self.profile.values['STEP_SETTLE_TIME'])
        deadline = time.monotonic() + settle
        while time.monotonic() < deadline:
            self.current_pose()
            time.sleep(.02)
        return self.current_pose()


class FakeDriver:
    """Explicit test driver. Never imports ROS; never substitutes for lost hardware."""
    def __init__(self, profile):
        self.profile = profile
        v = profile.values
        self.pose = {'x': v['HOME_X'], 'y': v['HOME_Y'], 'z': v['HOME_Z'], 'gripperAngle': 0,
                     'joints': {'joint1': 120, 'joint2': 90, 'joint3': 30}}
        self.executed = []

    def current_pose(self):
        return dict(self.pose)

    def calibration(self):
        return calibration_packet(self.profile.calibration)

    def preflight(self, positions):
        for position in positions:
            self.profile.position(position)

    def execute(self, step, expected):
        self.executed.append(dict(step))
        time.sleep(.025)
        if 'position' in step:
            self.pose.update(dict(zip(('x', 'y', 'z'), step['position'])))
        else:
            self.pose['gripperAngle'] = step['gripper']
        return self.current_pose()


class Executor:
    def __init__(self, driver, profile, clock=time.monotonic):
        self.driver, self.profile, self.clock = driver, profile, clock
        self.lock = threading.RLock()
        self.boot_id = str(uuid.uuid4())
        self.jobs = collections.OrderedDict()
        self.active = None
        self.thread = None

    def submit(self, request):
        manual = request.get('manualAction')
        keys = {'id', 'steps', 'expected', 'profileSha256', 'calibrationSha256'}
        if manual is not None:
            keys.add('manualAction')
        if set(request) != keys:
            raise ValueError('Solicitud de trayectoria inválida')
        identity = request['id']
        if not isinstance(identity, str) or str(uuid.UUID(identity)) != identity:
            raise ValueError('Identificador inválido')
        if request['profileSha256'] != self.profile.sha256:
            raise ValueError('Perfil diferente; sincroniza PC y brazo')
        fingerprint = hashlib.sha256(json.dumps(request, sort_keys=True).encode('utf-8')).hexdigest()
        if manual is not None and request['steps'] != self.profile.manual_steps(manual, request['expected']):
            raise ValueError('El control manual solo admite sus valores fijos')
        steps = self.profile.steps(request['steps'], manual)
        expected = request['expected']
        if (not isinstance(expected, dict) or set(expected) != {'x', 'y', 'z', 'gripperAngle'}
                or not all(finite(v) for v in expected.values()) or not 0 <= expected['gripperAngle'] <= 180):
            raise ValueError('Estado inicial inválido')
        self.profile.trajectory(steps, expected)
        with self.lock:
            if identity in self.jobs:
                if self.jobs[identity]['fingerprint'] != fingerprint:
                    raise ValueError('El identificador ya pertenece a otra trayectoria')
                return self.public(self.jobs[identity])
            if self.active is not None:
                raise RuntimeError('Ya existe una trayectoria activa')
            self.require_calibration(request['calibrationSha256'])
            self.driver.preflight([s['position'] for s in steps if 'position' in s])
            pose = self.driver.current_pose()
            if any(abs(pose[k] - expected[k]) > 3 for k in expected):
                raise RuntimeError('Posición inicial inesperada')
            job = {'id': identity, 'fingerprint': fingerprint, 'state': 'running', 'index': 0,
                   'completedSteps': 0, 'error': None, 'leaseUntil': self.clock() + 5,
                   'cancel': False, 'steps': steps, 'calibrationSha256': request['calibrationSha256']}
            self.jobs[identity], self.active = job, identity
            # Retain a bounded idempotency journal for this agent boot.
            while len(self.jobs) > 64:
                self.jobs.popitem(last=False)
            self.thread = threading.Thread(target=self.run, args=(job, expected), daemon=True, name='RonyTrajectory')
            self.thread.start()
            return self.public(job)

    def submit_manual(self, request):
        if set(request) != {'id', 'action', 'expected', 'profileSha256', 'calibrationSha256'}:
            raise ValueError('Solicitud de control manual inválida')
        translated = dict(request)
        action = translated.pop('action')
        translated['manualAction'] = action
        translated['steps'] = self.profile.manual_steps(action, translated['expected'])
        return self.submit(translated)

    def run(self, job, expected):
        try:
            for index, step in enumerate(job['steps']):
                with self.lock:
                    if job['cancel'] or self.clock() >= job['leaseUntil']:
                        job['state'] = 'cancelled'
                        job['error'] = 'Cancelado o conexión de control perdida; siguientes tramos bloqueados'
                        self.active = None
                        return
                    job['index'] = index
                self.require_calibration(job['calibrationSha256'])
                expected = self.driver.execute(step, expected)
                with self.lock:
                    job['completedSteps'] = index + 1
            with self.lock:
                job['state'] = 'completed'
                self.active = None
        except Exception as error:
            LOG.exception('Trayectoria %s detenida', job['id'])
            with self.lock:
                job['state'], job['error'] = 'fault', str(error)[:200]
                self.active = None
        finally:
            with self.lock:
                if self.active == job['id']:
                    self.active = None

    def public(self, job):
        return {key: job[key] for key in ('id', 'state', 'index', 'completedSteps', 'error')}

    def calibration(self):
        packet = self.driver.calibration()
        return dict(packet, schemaVersion=2, profileSha256=self.profile.sha256,
                    motionPolicy=MOTION_POLICY,
                    testMode=isinstance(self.driver, FakeDriver))

    def require_calibration(self, expected):
        if not isinstance(expected, str) or expected != self.driver.calibration()['sha256']:
            raise RuntimeError('La visión debe usar la calibración vigente del brazo')

    def state(self):
        try:
            pose, error = self.driver.current_pose(), None
        except Exception as exc:
            pose, error = None, str(exc)[:200]
        try:
            calibration_sha, calibration_error = self.driver.calibration()['sha256'], None
        except Exception as exc:
            calibration_sha, calibration_error = None, str(exc)[:200]
        with self.lock:
            last = next(reversed(self.jobs.values())) if self.jobs else None
            return {'schemaVersion': 2, 'bootId': self.boot_id, 'profileSha256': self.profile.sha256,
                    'motionPolicy': MOTION_POLICY,
                    'manualControlsVersion': 1,
                    'connected': pose is not None, 'pose': pose, 'error': error,
                    'calibrationSha256': calibration_sha, 'calibrationError': calibration_error,
                    'job': self.public(last) if last else None,
                    'encoderFeedback': False, 'objectPresenceConfirmed': False,
                    'testMode': isinstance(self.driver, FakeDriver)}

    def command(self, identity, action):
        with self.lock:
            job = self.jobs.get(identity)
            if job is None:
                raise ValueError('Trayectoria desconocida')
            if action == 'heartbeat' and job['state'] == 'running':
                job['leaseUntil'] = self.clock() + 5
            elif action == 'cancel':
                job['cancel'] = True
            return self.public(job)

    def close(self):
        with self.lock:
            if self.active:
                self.jobs[self.active]['cancel'] = True
        if self.thread:
            self.thread.join(timeout=12)


class Server(ThreadingMixIn, HTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def get_request(self):
        connection, address = super().get_request()
        connection.settimeout(5)
        return connection, address


def handler(executor, token):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, format_, *args):
            # Do not log bodies, tokens or incoming header values.
            LOG.debug('HTTP %s', self.command)

        def reply(self, code, value):
            raw = json.dumps(value, allow_nan=False, ensure_ascii=False).encode('utf-8')
            self.send_response(code)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(raw)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.end_headers()
            self.wfile.write(raw)

        def authorized(self):
            self.connection.settimeout(5)
            expected = ('Bearer ' + token).encode('utf-8')
            actual = self.headers.get('Authorization', '').encode('utf-8')
            if self.headers.get('Origin') or not hmac.compare_digest(actual, expected):
                self.close_connection = True
                self.reply(401, {'detail': 'Acceso al agente no autorizado'})
                return False
            return True

        def do_GET(self):
            if not self.authorized():
                return
            if self.path == '/v1/state':
                self.reply(200, executor.state())
            elif self.path == '/v1/calibration':
                try:
                    self.reply(200, executor.calibration())
                except Exception:
                    self.reply(503, {'detail': 'No se pudo leer la calibración del brazo'})
            else:
                self.reply(404, {'detail': 'Ruta no encontrada'})

        def do_POST(self):
            if not self.authorized():
                return
            try:
                if self.headers.get('Transfer-Encoding'):
                    raise ValueError('Transferencia no admitida')
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= 32768 or self.headers.get_content_type() != 'application/json':
                    raise ValueError('Se requiere un JSON de hasta 32 KiB')
                value = json.loads(self.rfile.read(length).decode('utf-8'),
                                   parse_constant=lambda _: (_ for _ in ()).throw(ValueError('Número inválido')))
                if not isinstance(value, dict):
                    raise ValueError('Se requiere un objeto')
                if self.path == '/v1/plans':
                    if 'manualAction' in value:
                        raise ValueError('Usa el endpoint de controles manuales')
                    self.reply(202, executor.submit(value))
                elif self.path == '/v1/manual':
                    self.reply(202, executor.submit_manual(value))
                elif self.path == '/v1/preflight':
                    if set(value) != {'positions', 'profileSha256', 'calibrationSha256'} or value['profileSha256'] != executor.profile.sha256:
                        raise ValueError('Perfil diferente')
                    if not isinstance(value['positions'], list) or len(value['positions']) > 16:
                        raise ValueError('Posiciones inválidas')
                    executor.require_calibration(value['calibrationSha256'])
                    checked = executor.driver.preflight(value['positions'])
                    self.reply(200, {'ok': True, 'positions': checked})
                elif self.path in ('/v1/heartbeat', '/v1/cancel'):
                    if set(value) != {'id'}:
                        raise ValueError('Identificador requerido')
                    self.reply(200, executor.command(value['id'], self.path.rsplit('/', 1)[1]))
                else:
                    self.reply(404, {'detail': 'Ruta no encontrada'})
            except (ValueError, TypeError, KeyError) as error:
                self.close_connection = True
                self.reply(422, {'detail': str(error)[:200]})
            except RuntimeError as error:
                self.reply(409, {'detail': str(error)[:200]})
            except Exception:
                LOG.exception('Error de comunicación con el controlador')
                self.close_connection = True
                self.reply(503, {'detail': 'Controlador no disponible'})
    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', required=True)
    parser.add_argument('--bind', default=os.getenv('RONY_AGENT_BIND', '127.0.0.1'))
    parser.add_argument('--port', type=int, default=8090)
    parser.add_argument('--fake', action='store_true', help='Prueba sin ROS ni actuadores')
    args = parser.parse_args()
    token = os.getenv('RONY_AGENT_TOKEN', '')
    if len(token) < 32 or len(token) > 256:
        raise SystemExit('Configura RONY_AGENT_TOKEN con 32 a 256 caracteres')
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
    profile = Profile(args.profile)
    lock_file = None
    if not args.fake:
        import fcntl
        lock_file = open('/tmp/rony-physical.lock', 'a')
        os.chmod('/tmp/rony-physical.lock', 0o600)
        fcntl.flock(lock_file, fcntl.LOCK_EX | fcntl.LOCK_NB)
    executor = Executor(FakeDriver(profile) if args.fake else RosDriver(profile), profile)
    server = Server((args.bind, args.port), handler(executor, token))
    LOG.info('Agente iniciado en %s:%s; fake=%s; arranque sin movimiento', args.bind, args.port, args.fake)
    try:
        server.serve_forever(poll_interval=.2)
    except KeyboardInterrupt:
        pass
    finally:
        executor.close()
        server.server_close()
        if lock_file:
            lock_file.close()


if __name__ == '__main__':
    main()
