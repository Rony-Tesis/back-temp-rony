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
    stop, reset_tracking = Event(), Event()
    calibration_lock = Lock()
    pending_calibration = [None]
    calibration_changed = Event()
    deferred_lock = Lock()
    deferred_targets = []
    motion_active = Event()
    def watch_parent():
        for line in sys.stdin:
            try:
                command = json.loads(line)
                if command.get('action') == 'reset-tracking':
                    reset_tracking.set()
                elif command.get('action') in ('set-calibration', 'clear-calibration'):
                    with calibration_lock:
                        pending_calibration[0] = command.get('packet') if command['action'] == 'set-calibration' else None
                        calibration_changed.set()
                elif command.get('action') == 'defer-target':
                    with deferred_lock:
                        deferred_targets.append((command['category'], float(command['x']), float(command['y'])))
                elif command.get('action') == 'motion-active':
                    motion_active.set() if command.get('active') is True else motion_active.clear()
            except (ValueError, AttributeError, TypeError, KeyError):
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
                        from runtime_profile import load_profile
                        profile = load_profile()
                        profile['calibration'] = None
                        source = MiraVision(os.environ['RONY_CAMERA_URL'], Path(os.environ['RONY_MODEL_PATH']), profile)
                        with calibration_lock:
                            source.set_calibration(pending_calibration[0])
                            calibration_changed.clear()
                    with deferred_lock:
                        for category, x, y in deferred_targets:
                            source.defer_target(category, x, y)
                        deferred_targets.clear()
                    if calibration_changed.is_set():
                        with calibration_lock:
                            source.set_calibration(pending_calibration[0])
                            calibration_changed.clear()
                    if reset_tracking.is_set():
                        source.reset_tracking()
                        reset_tracking.clear()
                    source.motion_active = motion_active.is_set()
                    value = source.read()
                emit({'kind': 'vision', 'jpeg': base64.b64encode(value.jpeg).decode('ascii'),
                      'detection': value.detection, 'stableFrames': value.stable_frames,
                      'targetDetection': value.target_detection, 'visibleCount': value.visible_count,
                      'calibrationSha256': source.calibration_sha})
            except Exception:
                if source is not None:
                    source.close()
                source = None
                emit({'kind': 'unavailable'})
                print('Fallo de visión: revisa modelo, dependencias y cámara.', file=sys.stderr, flush=True)
                stop.wait(2)
            stop.wait(max(0., 1 / fps - (time.monotonic() - started)))
    finally:
        if source is not None:
            source.close()

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Adaptador privado para Spring Boot')
    parser.add_argument('--kind', choices=('vision',), required=True)
    args = parser.parse_args()
    vision_worker()
