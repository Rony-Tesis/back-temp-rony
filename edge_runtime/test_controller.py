"""Robot-local multi-cycle tests using fake ROS/vision; never actuate physical hardware."""
import ast
import json
from pathlib import Path
import sys
import tempfile
import time
import unittest
import uuid
from collections import namedtuple

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'robot_agent'))
from agent import Executor, FakeDriver, Profile
from controller import Controller
from outbox import Outbox
import plans

PROFILE = Path(__file__).resolve().parents[2] / 'rony-yolo-isaac/runtime/rony_profile.json'
Observation = namedtuple('Observation', 'jpeg detection stable_frames target_detection visible_count')


class Vision:
    fps = 10
    def __init__(self, calibration):
        self.calibration, self.sequence, self.search, self.motion = calibration, 0, 0, False
        self.defer = []
    def latest(self):
        self.sequence += 1
        category = {1: 'plastic', 2: 'aluminum', 3: 'cardboard'}.get(self.search)
        target = {'residue': category, 'shortName': category, 'category': category,
                  'confidence': .95, 'pixel': {'x': 320, 'y': 240}, 'offsetMm': None,
                  'targetMm': {'x': 10., 'y': -210.}, 'destination': 'provisional'} if category else None
        return self.sequence, Observation(b'\xff\xd8\xff\xd9', target, 8, target, 1 if target else 0), time.monotonic(), self.calibration
    def reset_tracking(self): self.search += 1
    def defer_target(self, target): self.defer.append(target)
    def set_motion_active(self, value): self.motion = value


class ControllerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.profile = Profile(PROFILE)
        self.driver = FakeDriver(self.profile)
        self.executor = Executor(self.driver, self.profile)
        self.outbox = Outbox(Path(self.temporary.name) / 'events.sqlite3')
        self.vision = Vision(self.driver.calibration()['sha256'])
        self.connected = True
        self.controller = Controller(self.executor, self.vision, self.outbox, lambda: self.connected)
    def tearDown(self):
        self.executor.close()
        self.outbox.close()
        self.temporary.cleanup()
    def command(self, action, identity=None):
        return {'id': identity or str(uuid.uuid4()), 'action': action,
                'bootId': self.controller.boot_id, 'validForSeconds': 10}
    def until(self, predicate, seconds=6):
        deadline = time.monotonic() + seconds
        while not predicate() and time.monotonic() < deadline:
            self.controller.tick()
            time.sleep(.01)
        self.assertTrue(predicate(), self.controller.packet('jetmax-01', 1))

    def test_three_materials_run_locally_then_wait_for_more_objects(self):
        self.controller.enqueue(self.command('arm'))
        self.until(lambda: self.controller.cycles == 3 and self.controller.state == 'waiting')
        self.assertTrue(self.controller.auto)
        self.assertEqual(3, len(self.outbox.pending()))
        self.assertEqual(['plastic', 'aluminum', 'cardboard'], [o['category'] for o in self.outbox.pending()])
        self.assertTrue(all(o['result'] == 'unverified' and o['depositConfirmed'] is None for o in self.outbox.pending()))
        self.assertTrue(plans.near(self.driver.pose, plans.home(self.profile)))
        self.assertEqual(0, self.driver.pose['gripperAngle'])
        current = plans.home(self.profile)
        for step in self.driver.executed:
            if 'position' in step:
                target = step['position']
                self.profile.transition(current, target)
                current = target
        self.controller.enqueue(self.command('cancel'))
        self.controller.tick()
        self.assertFalse(self.controller.auto)

    def test_replayed_arm_command_executes_once(self):
        command = self.command('arm')
        self.controller.enqueue(command)
        self.controller.enqueue(command)
        self.controller.tick()
        self.until(lambda: self.controller.state == 'waiting')
        self.assertEqual(2, len(self.driver.executed))

    def test_command_from_previous_boot_is_rejected(self):
        command = self.command('arm')
        command['bootId'] = str(uuid.uuid4())
        with self.assertRaises(ValueError): self.controller.enqueue(command)
        self.assertEqual([], self.driver.executed)

    def test_manual_close_storage_and_reset_use_local_fixed_commands_without_cycles(self):
        self.controller.enqueue(self.command('close-gripper'))
        self.until(lambda: self.controller.state == 'manual' and not self.controller.active)
        self.assertEqual(90, self.driver.pose['gripperAngle'])
        self.assertEqual(1.0, self.driver.executed[-1]['duration'])
        self.controller.enqueue(self.command('park'))
        self.until(lambda: self.controller.state == 'parked' and not self.controller.active)
        self.assertEqual([-42.6878, -27.0905, 225.0], self.driver.executed[-1]['position'])
        self.assertFalse(self.controller.auto)
        self.assertEqual([], self.outbox.pending())
        self.assertEqual(0, self.controller.cycles)
        self.controller.enqueue(self.command('reset'))
        self.until(lambda: self.controller.state == 'idle' and not self.controller.active)
        self.assertTrue(plans.near(self.driver.pose, plans.home(self.profile)))
        self.assertEqual(0, self.driver.pose['gripperAngle'])

    def test_network_loss_blocks_motion_and_does_not_auto_resume(self):
        self.connected = False
        self.controller.enqueue(self.command('arm'))
        self.controller.tick()
        self.assertEqual('fault', self.controller.state)
        self.assertEqual([], self.driver.executed)
        self.connected = True
        self.controller.tick()
        self.assertFalse(self.controller.auto)

    def test_outbox_survives_restart_and_ack_is_idempotent(self):
        event = {'id': str(uuid.uuid4()), 'result': 'unverified'}
        self.outbox.operation(event)
        self.outbox.operation(event)
        self.assertEqual([event], self.outbox.pending())
        reopened = Outbox(Path(self.temporary.name) / 'events.sqlite3')
        try:
            self.assertEqual([event], reopened.pending())
        finally:
            reopened.close()
        self.outbox.acknowledge([event['id']])
        self.assertEqual([], self.outbox.pending())
        self.assertEqual(1, self.outbox.completed_cycles())

    def test_native_sources_parse_on_robot_python36(self):
        for name in ('controller.py', 'plans.py', 'outbox.py', 'run_edge.py', 'yolo_tensorrt.py'):
            source = (Path(__file__).parent / name).read_text(encoding='utf-8')
            ast.parse(source, feature_version=(3, 6))


if __name__ == '__main__':
    unittest.main()
