"""Video loss/backpressure must never queue old frames or authorize movement. No robot access."""
import ast
from pathlib import Path
import sys
import threading
import time
import unittest
from unittest.mock import patch

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from video import FrameWindow, VideoClient, render_preview


class VideoTest(unittest.TestCase):
    def test_bounded_window_blocks_and_releases_on_ack(self):
        window, stop = FrameWindow(), threading.Event()
        for sequence in (1, 2, 3): window.reserve(sequence, stop)
        completed = threading.Event()
        worker = threading.Thread(target=lambda: (window.reserve(90, stop), completed.set()))
        worker.start()
        self.assertFalse(completed.wait(.05))
        window.acknowledge(1)
        self.assertTrue(completed.wait(.5))
        worker.join(timeout=1)
        self.assertEqual({2, 3, 90}, set(window.pending))

    def test_slow_network_reconnects_instead_of_building_a_video_queue(self):
        window, stop = FrameWindow(), threading.Event()
        for sequence in (1, 2, 3): window.reserve(sequence, stop)
        with patch('video.time.monotonic', return_value=time.monotonic() + 2):
            with self.assertRaises(TimeoutError): window.reserve(4, stop)
        self.assertEqual(3, len(window.pending))

    def test_compression_only_changes_preview_not_inference_input(self):
        frame = np.random.default_rng(1).integers(0, 256, (480, 640, 3), dtype=np.uint8)
        ok, encoded = cv2.imencode('.jpg', frame, [cv2.IMWRITE_JPEG_QUALITY, 90])
        self.assertTrue(ok)
        original = encoded.tobytes()
        preview = render_preview(original, [(20, 30, 100, 120, 'plastic', .95)])
        self.assertLess(len(preview), len(original) * .8)
        self.assertEqual((480, 640, 3), cv2.imdecode(np.frombuffer(preview, np.uint8), cv2.IMREAD_COLOR).shape)
        self.assertEqual(original, encoded.tobytes())

    def test_video_is_not_control_health_and_refuses_unsafe_urls(self):
        for url in ('http://public.invalid', 'https://test-user:test-password@example.invalid', 'https://api.invalid?token=x'):
            with self.assertRaises(ValueError): VideoClient(url, 'x' * 64, 'boot', None, lambda: True)
        client = VideoClient('https://api.example.invalid', 'x' * 64, 'boot', None, lambda: False)
        self.assertFalse(client.healthy())
        self.assertEqual('wss://api.example.invalid/api/v1/device/video', client.url)

    def test_target_python_36_can_parse_video_and_vision_sources(self):
        root = Path(__file__).resolve().parents[1]
        for path in (root / 'edge_runtime/video.py', root / 'edge_runtime/run_edge.py', root / 'adapters/mira_vision.py'):
            ast.parse(path.read_text(encoding='utf-8'), feature_version=(3, 6))


if __name__ == '__main__': unittest.main()
