"""Native vision regression tests. Fake detections; no camera, SSH or actuators."""
import unittest
from types import SimpleNamespace
import numpy as np
from mira_vision import MiraVision, pixel_to_target

class MiraVisionTest(unittest.TestCase):
    def vision(self, category, confidence, shape=(480, 640, 3)):
        box = SimpleNamespace(cls=[0], conf=[confidence], xyxy=[[290, 210, 350, 270]])
        result = SimpleNamespace(names={0: category}, boxes=[box])
        source = MiraVision.__new__(MiraVision)
        source.model = SimpleNamespace(predict=lambda *args, **kwargs: [result])
        source.device = 'cpu'
        source.previous = None
        source.stable = 0
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

    def test_stable_plastic_requires_fifty_frames(self):
        source = self.vision('plastic', .91)
        for _ in range(50):
            observed = source.read()
        self.assertEqual(observed.stable_frames, 50)
        self.assertIsNotNone(observed.target_detection)
        self.assertGreater(len(observed.jpeg), 100)

if __name__ == '__main__':
    unittest.main()
