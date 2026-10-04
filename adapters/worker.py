"""Private JSON-lines native adapters. No HTTP server and no automatic motion plan."""
import argparse
import base64
import contextlib
import json
import os
from pathlib import Path
import sys
from threading import Event, Lock, Thread
import time

output = sys.stdout
write_lock = Lock()

def emit(value):
    line = json.dumps(value, ensure_ascii=False, allow_nan=False)
    with write_lock:
        output.write(line + '\n')
        output.flush()

def vision_worker():
    with contextlib.redirect_stdout(sys.stderr):
        from mira_vision import MiraVision
    stop = Event()
    def watch_parent():
        while sys.stdin.buffer.read(1):
            pass
        stop.set()
    Thread(target=watch_parent, name='parent-lifecycle', daemon=True).start()
    source = None
    fps = int(os.environ.get('RONY_FPS', '10'))
    try:
        while not stop.is_set():
            started = time.monotonic()
            try:
                with contextlib.redirect_stdout(sys.stderr):
                    if source is None:
                        source = MiraVision(os.environ['RONY_CAMERA_URL'], Path(os.environ['RONY_MODEL_PATH']))
                    value = source.read()
                emit({'kind': 'vision', 'jpeg': base64.b64encode(value.jpeg).decode('ascii'),
                      'detection': value.detection, 'stableFrames': value.stable_frames,
                      'targetDetection': value.target_detection})
            except Exception:
                if source is not None:
                    source.close()
                source = None
                emit({'kind': 'unavailable'})
                stop.wait(2)
            stop.wait(max(0., 1 / fps - (time.monotonic() - started)))
    finally:
        if source is not None:
            source.close()

def robot_worker():
    from jetmax_adapter import JetMaxRobot
    from worker_support import MotionStep
    robot = None
    try:
        robot = JetMaxRobot(os.environ.get('RONY_CREDENTIALS_NOTEBOOK'))
        robot.on_pose = lambda pose: emit({'kind': 'telemetry', 'pose': pose})
        emit({'kind': 'telemetry', 'pose': robot.current_pose()})
        for line in sys.stdin:
            if len(line) > 8192:
                break
            identity = None
            try:
                value = json.loads(line)
                identity = value['id']
                if value['action'] == 'preflight':
                    robot.preflight(value['positions'])
                    reply = {'kind': 'reply', 'id': identity, 'ok': True}
                elif value['action'] == 'execute':
                    step = MotionStep(value['stage'], 'Tramo solicitado por Java', value.get('position'),
                                      value['duration'], value.get('gripper'))
                    pose = robot.execute(step, value['expected'], Event())
                    reply = {'kind': 'reply', 'id': identity, 'ok': True, 'pose': pose}
                else:
                    raise ValueError('Comando no admitido')
                emit(reply)
            except Exception:
                emit({'kind': 'reply', 'id': identity, 'ok': False})
    except Exception:
        emit({'kind': 'unavailable'})
    finally:
        if robot is not None:
            robot.close()

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Adaptador privado para Spring Boot')
    parser.add_argument('--kind', choices=('vision', 'robot'), required=True)
    args = parser.parse_args()
    if args.kind == 'vision':
        vision_worker()
    else:
        robot_worker()
