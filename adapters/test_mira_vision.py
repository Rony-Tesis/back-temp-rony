"""Native vision regression tests. Fake detections; no camera, SSH or actuators."""
import unittest
import copy
import hashlib
import json
from types import SimpleNamespace
import numpy as np
from mira_vision import MiraVision, pixel_to_target
from runtime_profile import load_profile

class MiraVisionTest(unittest.TestCase):
    def vision(self, category, confidence, shape=(480, 640, 3)):
        box = SimpleNamespace(cls=[0], conf=[confidence], xyxy=[[290, 210, 350, 270]])
        result = SimpleNamespace(names={0: category}, boxes=[box])
        source = MiraVision.__new__(MiraVision)
        source.model = SimpleNamespace(predict=lambda *args, **kwargs: [result])
        source.profile = load_profile()
        source.capture = None
        source.device = 'cpu'
        source.previous = None
        source.stable = 0
        source.calibration_sha = None
        source.deferred = []
        source.motion_active = False
        source._frame = lambda: np.zeros(shape, dtype=np.uint8)
        return source

    def test_reference_projection_matches_notebook(self):
        x, y = pixel_to_target(320, 240, 640, 480)
        self.assertAlmostEqual(x, -9.017864488627762)
        self.assertAlmostEqual(y, -212.8648502748816)

    def test_rejects_wrong_aspect_and_pixels_outside_frame(self):
        with self.assertRaises(ValueError):
            pixel_to_target(320, 240, 1280, 720)
        with self.assertRaises(ValueError):
            pixel_to_target(640, 240, 640, 480)

    def test_aluminum_is_visible_but_not_a_robot_target(self):
        observed = self.vision('aluminum', .78).read()
        self.assertEqual(observed.detection['category'], 'aluminum')
        self.assertIsNone(observed.target_detection)
        self.assertEqual(observed.stable_frames, 0)

    def test_low_confidence_plastic_is_visible_but_does_not_authorize_grasp(self):
        observed = self.vision('plastic', .7).read()
        self.assertIsNotNone(observed.detection)
        self.assertIsNone(observed.target_detection)

    def test_stable_plastic_uses_eight_frames_from_current_notebook(self):
        source = self.vision('plastic', .91)
        for _ in range(8):
            observed = source.read()
        self.assertEqual(observed.stable_frames, 8)
        self.assertIsNotNone(observed.target_detection)
        self.assertGreater(len(observed.jpeg), 100)

    def test_all_enabled_materials_can_be_stable_targets(self):
        for category in ('plastic', 'aluminum', 'cardboard'):
            source = self.vision(category, .91)
            for _ in range(8):
                observed = source.read()
            self.assertEqual(category, observed.target_detection['category'])
            self.assertEqual(8, observed.stable_frames)

    def test_invalid_aspect_keeps_detection_but_blocks_pickup(self):
        observed = self.vision('plastic', .91, shape=(720, 1280, 3)).read()
        self.assertIsNotNone(observed.detection)
        self.assertIsNone(observed.target_detection)

    def test_tracking_reset_requires_new_stability_observations(self):
        source = self.vision('plastic', .91)
        for _ in range(8): source.read()
        source.reset_tracking()
        self.assertEqual(1, source.read().stable_frames)

    def calibration_packet(self, parameters):
        raw = json.dumps(parameters, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')
        return {'parameters': parameters, 'sha256': hashlib.sha256(raw).hexdigest()}

    def test_uses_robot_calibration_instead_of_notebook_and_resets_stability(self):
        source = self.vision('plastic', .91)
        for _ in range(8): source.read()
        before = pixel_to_target(320, 240, 640, 480, source.profile)
        parameters = copy.deepcopy(source.profile['calibration'])
        parameters['T'][0][0] += 3
        packet = self.calibration_packet(parameters)
        source.set_calibration(packet)
        self.assertEqual(0, source.stable)
        self.assertEqual(packet['sha256'], source.calibration_sha)
        after = pixel_to_target(320, 240, 640, 480, source.profile)
        self.assertNotEqual(before, after)
        self.assertEqual(load_profile()['calibration']['T'][0][0], parameters['T'][0][0] - 3)

    def test_missing_robot_calibration_keeps_image_but_blocks_coordinates(self):
        source = self.vision('plastic', .91)
        source.set_calibration(None)
        for _ in range(8): observed = source.read()
        self.assertGreater(len(observed.jpeg), 100)
        self.assertIsNotNone(observed.detection)
        self.assertIsNone(observed.detection['targetMm'])
        self.assertIsNone(observed.target_detection)
        self.assertEqual(0, observed.stable_frames)

    def test_corrupt_calibration_packet_is_rejected(self):
        source = self.vision('plastic', .91)
        packet = self.calibration_packet(source.profile['calibration'])
        packet['sha256'] = '0' * 64
        with self.assertRaises(ValueError): source.set_calibration(packet)

    def test_deferred_object_does_not_hide_another_material(self):
        source = self.vision('plastic', .95)
        first = source.read().target_detection
        source.defer_target('plastic', first['targetMm']['x'], first['targetMm']['y'])
        plastic = SimpleNamespace(cls=[0], conf=[.95], xyxy=[[290, 210, 350, 270]])
        aluminum = SimpleNamespace(cls=[1], conf=[.91], xyxy=[[350, 210, 410, 270]])
        result = SimpleNamespace(names={0: 'plastic', 1: 'aluminum'}, boxes=[plastic, aluminum])
        source.model = SimpleNamespace(predict=lambda *args, **kwargs: [result])
        for _ in range(8): observed = source.read()
        self.assertEqual('aluminum', observed.target_detection['category'])
        self.assertEqual(8, observed.stable_frames)
        self.assertEqual(1, len(source.deferred))

    def test_deferred_object_can_be_seen_again_after_it_is_removed(self):
        source = self.vision('plastic', .95)
        first = source.read().target_detection
        source.defer_target('plastic', first['targetMm']['x'], first['targetMm']['y'])
        self.assertIsNone(source.read().target_detection)
        saved_model = source.model
        source.model = SimpleNamespace(predict=lambda *args, **kwargs: [SimpleNamespace(boxes=[], names={})])
        for _ in range(12): source.read()
        self.assertEqual([], source.deferred)
        source.model = saved_model
        self.assertEqual('plastic', source.read().target_detection['category'])

    def test_moving_camera_does_not_clear_deferred_objects(self):
        source = self.vision('plastic', .95)
        target = source.read().target_detection['targetMm']
        source.defer_target('plastic', target['x'], target['y'])
        source.model = SimpleNamespace(predict=lambda *args, **kwargs: [SimpleNamespace(boxes=[], names={})])
        source.motion_active = True
        for _ in range(20): source.read()
        self.assertEqual(1, len(source.deferred))
        source.motion_active = False
        for _ in range(12): source.read()
        self.assertEqual([], source.deferred)

if __name__ == '__main__':
    unittest.main()
