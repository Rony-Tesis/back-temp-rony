"""Execution and lease regressions with fake drivers. Never import ROS or move hardware."""
import copy
import importlib.util
import json
from pathlib import Path
import threading
import time
import unittest
from unittest.mock import patch
import uuid

from agent import Executor, FakeDriver, Profile, RosDriver, calibration_packet

PROFILE = Path(__file__).resolve().parents[2] / 'rony-yolo-isaac/runtime/rony_profile.json'


class AgentTest(unittest.TestCase):
    def setUp(self):
        self.profile = Profile(PROFILE)
        self.driver = FakeDriver(self.profile)
        self.executor = Executor(self.driver, self.profile)

    def tearDown(self):
        self.executor.close()

    def request(self):
        return {'id': str(uuid.uuid4()), 'profileSha256': self.profile.sha256,
                'calibrationSha256': self.driver.calibration()['sha256'],
                'expected': {k: self.driver.current_pose()[k] for k in ('x', 'y', 'z', 'gripperAngle')},
                'steps': [{'stage': 3, 'label': 'cerrar', 'duration': 2, 'gripper': 80},
                          {'stage': 5, 'label': 'abrir', 'duration': 1, 'gripper': 0}]}

    def finish(self):
        self.executor.thread.join(timeout=3)
        self.assertFalse(self.executor.thread.is_alive())
        return self.executor.state()['job']

    def test_startup_only_observes(self):
        self.assertIsNone(self.executor.state()['job'])
        self.assertEqual([], self.driver.executed)

    def manual_request(self, action):
        value = self.request()
        value.pop('steps')
        value['action'] = action
        return value

    def test_named_manual_commands_use_exact_ros_values_and_reset_returns_home(self):
        self.executor.submit_manual(self.manual_request('close-gripper'))
        self.assertEqual('completed', self.finish()['state'])
        self.assertEqual({'stage': 0, 'label': 'Cerrando pinza manualmente a 90°',
                          'duration': 1.0, 'gripper': 90}, self.driver.executed[-1])
        self.executor.submit_manual(self.manual_request('park'))
        self.assertEqual('completed', self.finish()['state'])
        self.assertEqual([-42.6878, -27.0905, 225.0], self.driver.executed[-1]['position'])
        self.assertEqual(3.0, self.driver.executed[-1]['duration'])
        self.assertEqual(90, self.driver.current_pose()['gripperAngle'])
        reset = self.request()
        reset['steps'] = [{'stage': 0, 'label': 'HOME', 'duration': 3, 'position': [0, -162.94, 212.8]},
                          {'stage': 0, 'label': 'abrir', 'duration': 1, 'gripper': 0}]
        self.executor.submit(reset)
        self.assertEqual('completed', self.finish()['state'])
        self.assertEqual(0, self.driver.current_pose()['gripperAngle'])

    def test_manual_commands_reject_arbitrary_values_and_parking_away_from_home(self):
        request = self.manual_request('park')
        request['position'] = [0, 0, 225]
        with self.assertRaises(ValueError): self.executor.submit_manual(request)
        self.driver.pose.update(x=10, y=-220, z=90)
        with self.assertRaisesRegex(RuntimeError, 'HOME'):
            self.executor.submit_manual(self.manual_request('park'))
        self.assertEqual([], self.driver.executed)

    def test_automatic_plans_cannot_use_manual_gripper_or_storage_exception(self):
        for replacement in ({'stage': 0, 'label': 'close', 'duration': 1, 'gripper': 90},
                            {'stage': 0, 'label': 'store', 'duration': 3, 'position': [-42.6878, -27.0905, 225.0]}):
            request = self.request()
            request['steps'] = [replacement]
            with self.assertRaises(ValueError): self.executor.submit(request)
        with self.assertRaises(ValueError): self.profile.position([0, -162.94, 225])
        self.assertEqual([], self.driver.executed)

    def test_complete_plan_runs_on_agent_and_duplicate_does_not_reexecute(self):
        request = self.request()
        self.executor.submit(request)
        self.assertEqual('completed', self.finish()['state'])
        self.executor.submit(request)
        self.assertEqual(2, len(self.driver.executed))
        self.assertEqual(0, self.driver.current_pose()['gripperAngle'])
        changed = copy.deepcopy(request)
        changed['steps'][0]['duration'] = 1
        with self.assertRaises(ValueError):
            self.executor.submit(changed)

    def test_profile_mismatch_and_unsafe_targets_do_not_move(self):
        for mutation in ['profile', 'position', 'gripper', 'duration', 'nan']:
            request = self.request()
            if mutation == 'profile': request['profileSha256'] = 'old-profile'
            elif mutation == 'position': request['steps'][0] = {'stage': 2, 'label': 'outside', 'duration': 2, 'position': [101, -150, 120]}
            elif mutation == 'gripper': request['steps'][0]['gripper'] = 82
            elif mutation == 'duration': request['steps'][0]['duration'] = .1
            else: request['steps'][0]['duration'] = float('nan')
            with self.assertRaises(ValueError): self.executor.submit(request)
        self.assertEqual([], self.driver.executed)

    def test_cancel_finishes_issued_segment_and_blocks_next(self):
        entered, release = threading.Event(), threading.Event()
        original = self.driver.execute
        def blocked(step, expected):
            entered.set()
            self.assertTrue(release.wait(2))
            return original(step, expected)
        self.driver.execute = blocked
        request = self.request()
        self.executor.submit(request)
        self.assertTrue(entered.wait(1))
        self.executor.command(request['id'], 'cancel')
        release.set()
        self.assertEqual('cancelled', self.finish()['state'])
        self.assertEqual(1, len(self.driver.executed))

    def test_lost_owner_lease_blocks_next_segment(self):
        clock = [0.0]
        self.executor.clock = lambda: clock[0]
        original = self.driver.execute
        def lost_connection(step, expected):
            result = original(step, expected)
            clock[0] = 6
            return result
        self.driver.execute = lost_connection
        self.executor.submit(self.request())
        self.assertEqual('cancelled', self.finish()['state'])
        self.assertEqual(1, len(self.driver.executed))

    def test_driver_fault_blocks_remaining_commands(self):
        def failure(step, expected): raise RuntimeError('ROS disconnected')
        self.driver.execute = failure
        self.executor.submit(self.request())
        self.assertEqual('fault', self.finish()['state'])
        self.assertEqual(0, self.finish()['completedSteps'])

    def test_ros_calibration_is_authoritative_and_never_written(self):
        actual = copy.deepcopy(self.profile.calibration)
        actual['T'][0][0] += 3
        before = copy.deepcopy(actual)
        calls = []
        class Ros:
            def get_param(self, name):
                calls.append(name)
                return actual
        class Publisher:
            def get_num_connections(self): return 1
        class Kinematics:
            def inverse(self, size, point): return [120, 90, 0]
            def deg_to_pulse(self, angles): return [500, 500, 500]
        driver = RosDriver.__new__(RosDriver)
        driver.profile, driver.rospy = self.profile, Ros()
        driver.arm = driver.gripper = Publisher()
        driver.kinematics = Kinematics()
        driver.current_pose = self.driver.current_pose
        driver.preflight([[0, -162.94, 212.8]])
        packet = driver.calibration()
        self.assertEqual(before, packet['parameters'])
        self.assertNotEqual(calibration_packet(self.profile.calibration)['sha256'], packet['sha256'])
        self.assertEqual(before, actual)
        self.assertTrue(all(name == '/camera_cal/card_params' for name in calls))

    def test_stale_calibration_rejects_plan_without_moving(self):
        request = self.request()
        request['calibrationSha256'] = '0' * 64
        with self.assertRaisesRegex(RuntimeError, 'calibración vigente'):
            self.executor.submit(request)
        self.assertEqual([], self.driver.executed)

    def test_changed_calibration_stops_remaining_segments(self):
        original = self.driver.execute
        def changed(step, expected):
            result = original(step, expected)
            self.profile.calibration['T'][0][0] += 1
            return result
        self.driver.execute = changed
        self.executor.submit(self.request())
        result = self.finish()
        self.assertEqual('fault', result['state'])
        self.assertEqual(1, result['completedSteps'])
        self.assertEqual(1, len(self.driver.executed))

    def test_calibration_with_invalid_matrix_is_rejected(self):
        for invalid in ({}, {'K': [[0, 0, 0]] * 3, 'R': [[0]] * 3, 'T': [[0]] * 3}):
            with self.assertRaises(ValueError): calibration_packet(invalid)

    def test_transit_uses_only_profile_waypoints_without_expanding_pickup_area(self):
        for point in self.profile.transit:
            self.assertEqual(tuple(point), self.profile.position(point))
        with self.assertRaises(ValueError): self.profile.position([150, -50, 212.8])
        with self.assertRaises(ValueError): self.profile.position([153.1135, 55.7288, 90])

    def test_base_joint_keeps_ten_degree_margin_without_changing_servo_limits(self):
        class Ros:
            def get_param(inner, name): return self.profile.calibration
        class Publisher:
            def get_num_connections(inner): return 1
        class Kinematics:
            base = 230
            def inverse(inner, size, point): return [inner.base, 90, 0]
            def deg_to_pulse(inner, angles): return [angles[0] * 1000 / 240, 500, 500]
        driver = RosDriver.__new__(RosDriver)
        driver.profile, driver.rospy = self.profile, Ros()
        driver.arm = driver.gripper = Publisher()
        driver.kinematics = Kinematics()
        driver.current_pose = self.driver.current_pose
        checked = driver.preflight([[0, -162.94, 212.8]])
        self.assertEqual(230, checked[0]['baseAngleDeg'])
        driver.kinematics.base = 239
        with self.assertRaisesRegex(ValueError, 'Giro de base'):
            driver.preflight([[0, -162.94, 212.8]])

    def test_reported_setpoint_does_not_skip_motion_or_settle_timers(self):
        clock = [0.0]
        pose = {'x': 0, 'y': -162.94, 'z': 212.8, 'gripperAngle': 0}
        class Message: pass
        class Publisher:
            def get_num_connections(self): return 1
            def publish(self, message): pose['gripperAngle'] = message.data
        driver = RosDriver.__new__(RosDriver)
        driver.profile = self.profile
        driver.SetServo = Message
        driver.gripper = Publisher()
        driver.current_pose = lambda: dict(pose)
        with patch('agent.time.monotonic', side_effect=lambda: clock[0]), patch('agent.time.sleep', side_effect=lambda seconds: clock.__setitem__(0, clock[0] + seconds)):
            driver.execute({'gripper': 80, 'duration': 2}, dict(pose))
        self.assertGreaterEqual(clock[0], 3.4)

    def test_low_lateral_or_diagonal_plan_is_rejected_before_any_command(self):
        for target in ([0, -162.94, 212.8], [0, -200, 90], [20, -200, 120]):
            self.driver.pose.update(x=10, y=-220, z=90, gripperAngle=80)
            request = self.request()
            request['steps'] = [{'stage': 4, 'label': 'unsafe', 'duration': 3, 'position': target}]
            with self.assertRaisesRegex(ValueError, 'primero eleva verticalmente'):
                self.executor.submit(request)
            self.assertEqual([], self.driver.executed)
            self.assertIsNone(self.executor.state()['job'])

    def test_disposal_requires_home_after_lifting_the_object(self):
        self.driver.pose.update(x=10, y=-220, z=90, gripperAngle=80)
        request = self.request()
        request['steps'] = [
            {'stage': 4, 'label': 'lift', 'duration': 3, 'position': [10, -220, 212.8]},
            {'stage': 4, 'label': 'skip HOME', 'duration': 3, 'position': self.profile.transit[1]}]
        with self.assertRaisesRegex(ValueError, 'vuelve a HOME antes de girar'):
            self.executor.submit(request)
        self.assertEqual([], self.driver.executed)

    def test_vertical_lift_then_home_then_disposal_is_accepted(self):
        self.driver.pose.update(x=10, y=-220, z=90, gripperAngle=80)
        request = self.request()
        request['steps'] = [
            {'stage': 4, 'label': 'lift vertically', 'duration': 3, 'position': [10, -220, 212.8]},
            {'stage': 4, 'label': 'HOME', 'duration': 3, 'position': [0, -162.94, 212.8]},
            {'stage': 4, 'label': 'turn high', 'duration': 3, 'position': self.profile.transit[1]}]
        self.executor.submit(request)
        self.assertEqual('completed', self.finish()['state'])
        self.assertEqual(3, len(self.driver.executed))

    def test_ros_driver_rechecks_low_lateral_move_before_publishing(self):
        driver = RosDriver.__new__(RosDriver)
        driver.profile = self.profile
        pose = {'x': 10, 'y': -220, 'z': 90, 'gripperAngle': 80}
        driver.current_pose = lambda: dict(pose)
        # No publisher is provided: rejection must occur before constructing/publishing a message.
        with self.assertRaisesRegex(ValueError, 'primero eleva verticalmente'):
            driver.execute({'position': [0, -162.94, 212.8], 'duration': 3}, pose)

    def test_exported_values_match_current_notebook(self):
        path = PROFILE.with_name('export_notebook.py')
        spec = importlib.util.spec_from_file_location('export_notebook', path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.assertEqual(module.export(), json.loads(PROFILE.read_text(encoding='utf-8')))


if __name__ == '__main__':
    unittest.main()
