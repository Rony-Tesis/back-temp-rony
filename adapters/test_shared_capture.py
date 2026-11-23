"""One camera reader must deliver the same latest image to preview and inference independently."""
import threading
import unittest
from unittest.mock import patch

from mira_vision import LatestMjpegCapture


class SharedCaptureTest(unittest.TestCase):
    def test_preview_never_consumes_the_inference_frame_and_buffered_old_frames_are_dropped(self):
        one = b'\xff\xd8old\xff\xd9'
        two = b'\xff\xd8new\xff\xd9'
        release = threading.Event()
        class Response:
            first = True
            def __enter__(self): return self
            def __exit__(self, *_): pass
            def read1(self, _):
                if self.first:
                    self.first = False
                    return b'--camera\r\n' + one + b'\r\n' + two
                release.wait(2)
                return b''
        with patch('mira_vision.urlopen', return_value=Response()) as opening:
            capture = LatestMjpegCapture('http://camera.example.invalid')
            try:
                sequence, preview, _ = capture.latest(0)
                self.assertEqual(two, preview)
                self.assertEqual(two, capture.read())
                self.assertEqual((sequence, preview), capture.latest(0)[:2])
                opening.assert_called_once()
            finally:
                capture.stop_event.set()
                release.set()
                capture.close()


if __name__ == '__main__': unittest.main()
