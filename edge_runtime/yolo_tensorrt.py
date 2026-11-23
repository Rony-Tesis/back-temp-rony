"""YOLO11 detect inference for Jetson's existing TensorRT 8 / Python 3.6. No PyTorch."""
import hashlib
import gc
import json
from pathlib import Path
import threading
from types import SimpleNamespace

import cv2
import numpy as np


def preprocess(image, size=640):
    height, width = image.shape[:2]
    gain = min(float(size) / height, float(size) / width)
    scaled = (int(round(width * gain)), int(round(height * gain)))
    left = int(round((size - scaled[0]) / 2 - .1))
    top = int(round((size - scaled[1]) / 2 - .1))
    canvas = np.full((size, size, 3), 114, dtype=np.uint8)
    canvas[top:top + scaled[1], left:left + scaled[0]] = cv2.resize(image, scaled)
    tensor = np.ascontiguousarray(canvas[:, :, ::-1].transpose(2, 0, 1)[None], dtype=np.float32) / 255.
    return tensor, gain, left, top


def decode(output, shape, gain, left, top, confidence=.6, iou=.3, maximum=20):
    output = np.asarray(output, dtype=np.float32)
    if output.shape != (1, 7, 8400):
        raise ValueError('Unexpected YOLO11 output layout: ' + str(output.shape))
    rows = output[0].T
    scores = rows[:, 4:].max(axis=1)
    labels = rows[:, 4:].argmax(axis=1)
    selected = np.isfinite(rows).all(axis=1) & (scores >= confidence)
    rows, scores, labels = rows[selected], scores[selected], labels[selected]
    boxes = np.empty((len(rows), 4), dtype=np.float32)
    boxes[:, :2] = rows[:, :2] - rows[:, 2:4] / 2
    boxes[:, 2:] = rows[:, :2] + rows[:, 2:4] / 2
    order, keep = scores.argsort(kind='stable')[::-1], []
    while len(order) and len(keep) < maximum:
        first, rest = order[0], order[1:]
        keep.append(first)
        if not len(rest):
            break
        intersection = np.maximum(0., np.minimum(boxes[first, 2:], boxes[rest, 2:])
                                  - np.maximum(boxes[first, :2], boxes[rest, :2])).prod(axis=1)
        area = np.maximum(0., boxes[:, 2:] - boxes[:, :2]).prod(axis=1)
        overlap = intersection / np.maximum(area[first] + area[rest] - intersection, 1e-7)
        order = rest[overlap <= iou]
    result = []
    height, width = shape[:2]
    for index in keep:
        box = boxes[index].copy()
        box[[0, 2]] = np.clip((box[[0, 2]] - left) / gain, 0, width)
        box[[1, 3]] = np.clip((box[[1, 3]] - top) / gain, 0, height)
        result.append(SimpleNamespace(cls=[int(labels[index])], conf=[float(scores[index])], xyxy=[box]))
    return result


class TensorRtYolo:
    def __init__(self, directory):
        import tensorrt as trt
        import pycuda.driver as cuda
        directory = Path(directory)
        manifest = json.loads((directory / 'engine.json').read_text())
        raw = (directory / 'yolo11n.engine').read_bytes()
        if not manifest.get('engineBuiltOnTarget') or hashlib.sha256(raw).hexdigest() != manifest['engineSha256']:
            raise ValueError('Engine checksum/build record mismatch')
        if manifest['tensorRtVersion'] != trt.__version__:
            raise ValueError('Rebuild engine with the installed TensorRT version')
        self.names = {int(key): value for key, value in manifest['classNames'].items()}
        if self.names != {0: 'aluminum', 1: 'cardboard', 2: 'plastic'}:
            raise ValueError('Unexpected trained classes')
        cuda.init()
        self.cuda, self.cuda_context = cuda, cuda.Device(0).make_context()
        self.owner = threading.get_ident()
        self.logger = trt.Logger(trt.Logger.WARNING)
        self.runtime = trt.Runtime(self.logger)
        self.engine = self.runtime.deserialize_cuda_engine(raw)
        if self.engine is None:
            raise RuntimeError('Could not load TensorRT engine')
        self.execution = self.engine.create_execution_context()
        self.bindings, self.inputs, self.outputs = [], [], []
        self.stream = cuda.Stream()
        for index in range(self.engine.num_bindings):
            shape = tuple(self.engine.get_binding_shape(index))
            if min(shape) < 1:
                raise ValueError('Only static batch-1 engines are supported')
            host = cuda.pagelocked_empty(int(trt.volume(shape)), trt.nptype(self.engine.get_binding_dtype(index)))
            device = cuda.mem_alloc(host.nbytes)
            self.bindings.append(int(device))
            (self.inputs if self.engine.binding_is_input(index) else self.outputs).append((host, device, shape))
        if len(self.inputs) != 1 or len(self.outputs) != 1 or self.inputs[0][2] != (1, 3, 640, 640):
            raise ValueError('Engine bindings do not match the verified export')

    def predict(self, image, conf=.6, iou=.3, max_det=20, **unused):
        if threading.get_ident() != self.owner:
            raise RuntimeError('TensorRT inference must run on its owning thread')
        tensor, gain, left, top = preprocess(image)
        host, device, shape = self.inputs[0]
        np.copyto(host, tensor.ravel(), casting='unsafe')
        self.cuda.memcpy_htod_async(device, host, self.stream)
        if not self.execution.execute_async_v2(self.bindings, self.stream.handle):
            raise RuntimeError('TensorRT execution failed')
        for output_host, output_device, output_shape in self.outputs:
            self.cuda.memcpy_dtoh_async(output_host, output_device, self.stream)
        self.stream.synchronize()
        output_host, _, output_shape = self.outputs[0]
        boxes = decode(output_host.reshape(output_shape), image.shape, gain, left, top, conf, iou, max_det)
        return [SimpleNamespace(names=self.names, boxes=boxes)]

    def close(self):
        if self.cuda_context is not None:
            self.stream.synchronize()
            for host, device, shape in self.inputs + self.outputs:
                device.free()
            self.inputs, self.outputs, self.bindings = [], [], []
            self.execution = None
            self.engine = None
            self.runtime = None
            self.stream = None
            gc.collect()
            self.cuda_context.pop()
            self.cuda_context.detach()
            self.cuda_context = None
