"""Autonomous JetMax classification coordinator. All perception/planning/motion stays on robot."""
import math
import queue
import threading
import time
import uuid
from datetime import datetime, timezone

from agent import MOTION_POLICY, STORAGE_POSITION
import plans


class Controller:
    def __init__(self, executor, vision, outbox, cloud_healthy, boot_id=None):
        self.executor, self.profile, self.vision, self.outbox = executor, executor.profile, vision, outbox
        self.cloud_healthy = cloud_healthy
        self.boot_id = boot_id or str(uuid.uuid4())
        self.lock, self.commands = threading.RLock(), queue.Queue()
        self.state, self.label, self.error = 'idle', 'Listo para activar; YOLO y ciclo dentro del JetMax', None
        self.auto, self.active = False, False
        self.job_id, self.purpose, self.target, self.cycle_id = None, None, None, None
        self.previous, self.repeats, self.baseline, self.empty, self.last_sequence = None, 0, 0, 0, 0
        self.cycles = outbox.completed_cycles()
        self.stage, self.stage_started, self.started = 0, 0., 0.
        self.times, self.last_index = [0.] * 6, -1

    def enqueue(self, command):
        if (not isinstance(command, dict) or command.get('bootId') != self.boot_id
                or command.get('action') not in ('arm', 'cancel', 'reset', 'close-gripper', 'park')
                or not 0 < command.get('validForSeconds', 0) <= 10
                or str(uuid.UUID(command['id'])) != command['id']):
            raise ValueError('Comando remoto inválido o de otra sesión')
        if self.outbox.claim(command):
            self.commands.put((command['action'], time.monotonic() + command['validForSeconds']))

    def _guard(self, require_vision=True):
        if not self.cloud_healthy():
            raise RuntimeError('Conexión HTTPS perdida; siguientes movimientos bloqueados')
        if require_vision:
            sequence, observation, observed_at, calibration = self.vision.latest()
            if observation is None or time.monotonic() - observed_at >= 5:
                raise RuntimeError('Cámara o YOLO sin observación reciente')
            if calibration != self.executor.driver.calibration()['sha256']:
                raise RuntimeError('La visión debe usar la calibración vigente del brazo')

    def _start(self, purpose, steps):
        manual = purpose in ('close-gripper', 'park')
        self._guard(purpose not in ('reset', 'close-gripper', 'park'))
        expected = self.executor.driver.current_pose()
        identity = str(uuid.uuid4())
        request = {'id': identity, 'steps': steps,
            'expected': {key: expected[key] for key in ('x', 'y', 'z', 'gripperAngle')},
            'profileSha256': self.profile.sha256,
            'calibrationSha256': self.executor.driver.calibration()['sha256']}
        if manual:
            request.pop('steps')
            request['action'] = purpose
            self.executor.submit_manual(request)
        else:
            self.executor.submit(request)
        self.job_id, self.purpose, self.active = identity, purpose, True
        self.state = 'manual' if manual else {'prepare': 'preparing', 'sort': 'moving', 'reset': 'resetting'}[purpose]
        self.started = self.stage_started = time.monotonic()
        self.times, self.last_index = [0.] * 6, -1
        self.vision.set_motion_active(True)

    def _waiting(self):
        self.vision.reset_tracking()
        self.baseline = self.vision.latest()[0]
        self.state, self.label = 'waiting', 'Analizando de nuevo; esperando 8 observaciones recientes'
        self.active, self.target, self.cycle_id, self.job_id = False, None, None, None
        self.stage, self.started, self.stage_started, self.times = 1, 0., 0., [0.] * 6
        self.empty, self.last_sequence = 0, self.baseline

    def _record(self, result):
        if self.cycle_id is None or self.target is None:
            return
        self.outbox.operation({'id': self.cycle_id,
            'completedAt': datetime.now(timezone.utc).isoformat(), 'residue': self.target['residue'],
            'category': self.target['category'], 'confidence': self.target['confidence'],
            'result': result, 'durationMs': min(86400000, int((time.monotonic() - self.started) * 1000)),
            'destination': self.profile.data['bins'][self.target['category']]['label'] + ' · destino provisional',
            'depositConfirmed': None})
        self.cycle_id = None

    def _fault(self, error):
        if self.job_id:
            self.executor.command(self.job_id, 'cancel')
        self._record('failure')
        self.auto = False
        self.state, self.label, self.error = 'fault', 'Movimiento bloqueado; revisión y reset requeridos', str(error)[:200]

    def _command(self, action):
        if action == 'cancel':
            self.auto = False
            if self.active:
                self.executor.command(self.job_id, 'cancel')
                self.label = 'Cancelando después del tramo emitido'
            else:
                self.state, self.label = 'cancelled', 'Automático desactivado'
            return
        if self.active:
            raise RuntimeError('Espera que termine el tramo antes de iniciar o resetear')
        if action in ('close-gripper', 'park'):
            if self.auto or self.state == 'fault':
                raise RuntimeError('Detén el automático y espera antes de usar controles manuales')
            pose = self.executor.driver.current_pose()
            steps = self.profile.manual_steps(action, pose)
            self.error, self.target, self.cycle_id = None, None, None
            self._start(action, steps)
            return
        if action == 'reset':
            self.auto = False
            self.error = None
            self._start('reset', plans.reset(self.profile, self.executor.driver.current_pose()))
            return
        if self.state not in ('idle', 'finished'):
            raise RuntimeError('Revisa y resetea el brazo antes de iniciar')
        self.error, self.previous, self.repeats, self.auto = None, None, 0, True
        self._start('prepare', plans.prepare(self.profile, self.executor.driver.current_pose()))

    def tick(self):
        with self.lock:
            try:
                while not self.commands.empty():
                    action, deadline = self.commands.get_nowait()
                    if time.monotonic() <= deadline:
                        self._command(action)
                self.executor.driver.current_pose()
                if self.active:
                    state = self.executor.state()['job']
                    if state is None or state['id'] != self.job_id:
                        raise RuntimeError('El ejecutor perdió la trayectoria local')
                    if state['index'] != self.last_index:
                        now = time.monotonic()
                        if self.stage_started:
                            self.times[self.stage] += now - self.stage_started
                        self.last_index, self.stage_started = state['index'], now
                        job = self.executor.jobs[self.job_id]
                        self.stage = job['steps'][state['index']]['stage']
                        self.label = job['steps'][state['index']]['label']
                    if state['state'] == 'running':
                        self._guard(self.purpose not in ('reset', 'close-gripper', 'park'))
                        self.executor.command(self.job_id, 'heartbeat')
                        return
                    self.active = False
                    self.vision.set_motion_active(False)
                    if state['state'] != 'completed':
                        self._record('failure')
                        self.auto = False
                        if self.state != 'fault':
                            self.state = 'cancelled' if state['state'] == 'cancelled' else 'fault'
                            self.error = state['error']
                            self.label = 'Secuencia detenida; revisión requerida'
                        return
                    if self.state == 'fault':
                        return
                    if self.purpose in ('close-gripper', 'park'):
                        parked = plans.near(self.executor.driver.current_pose(), STORAGE_POSITION)
                        self.state = 'parked' if parked else 'manual'
                        self.label = ('Brazo en posición de guardado; Resetear para volver a HOME' if parked else
                                      'Pinza cerrada manualmente a 90°; Resetear antes de clasificar')
                        self.job_id, self.target, self.error = None, None, None
                        return
                    if self.purpose == 'sort':
                        self._record('unverified')
                        self.cycles += 1
                    if self.purpose == 'reset':
                        self.vision.reset_tracking()
                        self.job_id, self.error, self.target, self.previous, self.repeats = None, None, None, None, 0
                        self.state, self.label = 'idle', 'Reset completo; HOME y pinza abierta'
                    elif self.auto:
                        self._waiting()
                    else:
                        self.state, self.label = 'cancelled', 'Automático desactivado'
                        self.target, self.job_id = None, None
                    return
                if not self.auto or self.state != 'waiting':
                    return
                self._guard()
                sequence, observation, observed_at, calibration = self.vision.latest()
                if sequence == self.last_sequence:
                    return
                self.last_sequence = sequence
                self.empty = self.empty + 1 if observation.visible_count == 0 else 0
                if self.empty >= self.profile.values['EMPTY_CONFIRM_FRAMES']:
                    self.previous, self.repeats = None, 0
                    self.label = 'Mesa vacía; esperando nuevos residuos, automático activo'
                target = observation.target_detection
                if target is None or min(observation.stable_frames, sequence - self.baseline) < self.profile.values['STABLE_FRAMES']:
                    return
                same = self.previous and target['category'] == self.previous['category'] and math.hypot(
                    target['targetMm']['x'] - self.previous['targetMm']['x'],
                    target['targetMm']['y'] - self.previous['targetMm']['y']) < self.profile.values['REPEAT_RADIUS_MM']
                self.repeats = self.repeats + 1 if same else 1
                if self.repeats > self.profile.values['MAX_REPEATS_SAME_OBJECT']:
                    self.vision.defer_target(target)
                    self.previous, self.repeats = None, 0
                    self._waiting()
                    self.label = 'Residuo persistente omitido; buscando otros residuos'
                    return
                pose = self.executor.driver.current_pose()
                if not plans.near(pose, plans.home(self.profile)) or pose['gripperAngle'] > 3:
                    raise RuntimeError('Se requiere HOME y pinza abierta antes de recoger')
                self.previous = self.target = target
                self.cycle_id = str(uuid.uuid4())
                self.started = time.monotonic()
                self._start('sort', plans.sort(self.profile, target))
            except Exception as error:
                self._fault(error)

    def packet(self, device_id, sequence):
        with self.lock:
            now = time.monotonic()
            seq, observation, observed_at, calibration = self.vision.latest()
            camera_online = observation is not None and now - observed_at < 5
            try:
                pose = self.executor.driver.current_pose()
            except Exception:
                pose = None
            online = pose is not None and self.state != 'fault'
            times = list(self.times)
            if self.active and self.stage_started:
                times[self.stage] += now - self.stage_started
            milliseconds = [min(86400000, int(value * 1000)) for value in times]
            stage_elapsed = min(86400000, int((now - self.stage_started) * 1000)) if self.active else 0
            return {'deviceId': device_id, 'bootId': self.boot_id, 'sequence': sequence,
                'profileSha256': self.profile.sha256, 'motionPolicy': MOTION_POLICY,
                'robotState': {'connected': online, 'state': self.state, 'stepLabel': self.label,
                    'autoEnabled': self.auto, 'cycleActive': self.active, 'holdingObject': None,
                    'completedGrasps': self.cycles, 'reportedPosition': {key: pose[key] for key in ('x', 'y', 'z')} if pose else None,
                    'gripperAngle': pose['gripperAngle'] if pose else None, 'reportedJoints': pose.get('joints') if pose else None,
                    'telemetryAt': None, 'error': self.error, 'completedCycles': self.cycles, 'provisionalDestinations': True},
                'detection': observation.detection if camera_online else None,
                'targetDetection': self.target if self.active else (observation.target_detection if camera_online else None),
                'process': {'stageIndex': self.stage, 'elapsedMs': min(86400000, int((now - self.started) * 1000)) if self.active else 0,
                    'stageElapsedMs': stage_elapsed, 'stageDurationsMs': milliseconds, 'progressPercent': self.stage / 6. * 100,
                    'completed': False, 'lastResult': None, 'gripperClosed': bool(pose and pose['gripperAngle'] >= 75)},
                'cameraOnline': camera_online, 'stableFrames': min(8, observation.stable_frames, max(0, seq - self.baseline)) if camera_online else 0,
                'requiredStableFrames': int(self.profile.values['STABLE_FRAMES']), 'framesPerSecond': self.vision.fps,
                'jpeg': None, 'operations': self.outbox.pending(), 'acknowledgedCommands': []}
