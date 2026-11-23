"""Portable preprocessing/NMS tests; never import CUDA or control actuators."""
import unittest
import numpy as np
from yolo_tensorrt import decode, preprocess


class YoloDecodeTest(unittest.TestCase):
    def test_letterbox_bgr_to_rgb_and_coordinates(self):
        image = np.zeros((480, 640, 3), dtype=np.uint8)
        image[:, :, 2] = 255
        tensor, gain, left, top = preprocess(image)
        self.assertEqual((1, 3, 640, 640), tensor.shape)
        self.assertEqual((1., 0, 80), (gain, left, top))
        self.assertEqual(1., tensor[0, 0, 100, 100])
        self.assertEqual(0., tensor[0, 2, 100, 100])
        output = np.zeros((1, 7, 8400), dtype=np.float32)
        output[0, :, 0] = [320, 320, 60, 40, .1, .2, .95]
        boxes = decode(output, image.shape, gain, left, top)
        self.assertEqual(2, boxes[0].cls[0])
        np.testing.assert_allclose(boxes[0].xyxy[0], [290, 220, 350, 260])

    def test_agnostic_nms_removes_overlapping_different_classes(self):
        output = np.zeros((1, 7, 8400), dtype=np.float32)
        output[0, :, 0] = [320, 320, 60, 40, .91, .1, .1]
        output[0, :, 1] = [320, 320, 60, 40, .1, .95, .1]
        boxes = decode(output, (480, 640, 3), 1, 0, 80)
        self.assertEqual(1, len(boxes))
        self.assertEqual(1, boxes[0].cls[0])

    def test_empty_or_nonfinite_predictions_do_not_produce_targets(self):
        output = np.zeros((1, 7, 8400), dtype=np.float32)
        output[0, :, 0] = [320, 320, 60, 40, np.nan, .95, .1]
        self.assertEqual([], decode(output, (480, 640, 3), 1, 0, 80))


if __name__ == '__main__':
    unittest.main()
