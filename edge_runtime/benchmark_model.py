"""Real-camera inference benchmark only. No ROS imports or actuator commands."""
import argparse
import json
from pathlib import Path
import time
from urllib.request import urlopen

import cv2
import numpy as np
from yolo_tensorrt import TensorRtYolo


def camera_image(url):
    with urlopen(url, timeout=5) as response:
        buffer = b''
        started = time.monotonic()
        while time.monotonic() - started < 8:
            buffer += response.read1(65536)
            if len(buffer) > 4 * 1024 * 1024:
                raise ValueError('Camera frame exceeds limit')
            start, end = buffer.find(b'\xff\xd8'), buffer.find(b'\xff\xd9')
            if 0 <= start < end:
                image = cv2.imdecode(np.frombuffer(buffer[start:end + 2], np.uint8), cv2.IMREAD_COLOR)
                if image is None:
                    raise ValueError('Invalid camera JPEG')
                return image
    raise RuntimeError('Camera did not provide a JPEG')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model-dir', required=True)
    parser.add_argument('--camera-url', default='http://127.0.0.1:8080/stream?topic=/usb_cam/image_rect_color')
    args = parser.parse_args()
    directory = Path(args.model_dir)
    detector = TensorRtYolo(directory)
    try:
        image = camera_image(args.camera_url)
        detector.predict(image)
        durations = []
        for _ in range(5):
            started = time.monotonic()
            result = detector.predict(image)[0]
            durations.append(time.monotonic() - started)
        cv2.imwrite(str(directory / 'benchmark-frame.jpg'), image)
        report = {'ok': True, 'inferenceOn': 'Jetson Nano / TensorRT / GPU',
                  'imageShape': list(image.shape), 'inferenceSeconds': durations,
                  'meanInferenceSeconds': sum(durations) / len(durations),
                  'detections': [{'category': result.names[box.cls[0]], 'confidence': box.conf[0],
                                  'xyxy': [float(value) for value in box.xyxy[0]]} for box in result.boxes],
                  'motionCommandsSent': 0, 'rosParametersWritten': 0}
        (directory / 'benchmark.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))
    finally:
        detector.close()


if __name__ == '__main__':
    main()
