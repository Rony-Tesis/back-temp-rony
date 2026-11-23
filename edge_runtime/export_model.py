"""Export the current trained weights for Jetson TensorRT. Runs on the PC, no motion."""
import hashlib
import json
import os
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[2]
YOLO_ROOT = ROOT / 'rony-yolo-isaac'
OUTPUT = Path(__file__).with_name('dist') / 'model'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    os.environ['YOLO_AUTOINSTALL'] = 'False'
    import numpy as np
    import onnx
    import onnxruntime as ort
    import torch
    from ultralytics import YOLO
    profile = json.loads((YOLO_ROOT / 'runtime/rony_profile.json').read_text(encoding='utf-8'))
    source = YOLO_ROOT / profile['model']
    OUTPUT.mkdir(parents=True, exist_ok=True)
    copied = OUTPUT / 'yolo11n.pt'
    shutil.copyfile(source, copied)
    model = YOLO(str(copied))
    assert model.names == {0: 'aluminum', 1: 'cardboard', 2: 'plastic'}
    exported = Path(model.export(format='onnx', opset=12, imgsz=640, batch=1,
                                 dynamic=False, simplify=False, nms=False, device='cpu', quantize=32))
    graph = onnx.load(str(exported))
    onnx.checker.check_model(graph)
    session = ort.InferenceSession(str(exported), providers=['CPUExecutionProvider'])
    rng = np.random.RandomState(42)
    parity = []
    model.model.cpu().float().eval()
    for image in (np.zeros((1, 3, 640, 640), dtype=np.float32), rng.rand(1, 3, 640, 640).astype(np.float32)):
        with torch.inference_mode():
            expected = model.model(torch.from_numpy(image))[0].numpy()
        actual = session.run(None, {'images': image})[0]
        assert actual.shape == (1, 7, 8400), 'Unexpected output layout'
        np.testing.assert_allclose(actual, expected, rtol=.005, atol=.05)
        parity.append(float(np.max(np.abs(actual - expected))))
    manifest = {'schemaVersion': 1, 'sourceModel': profile['model'], 'sourceSha256': sha(source),
                'onnxSha256': sha(exported), 'inputShape': [1, 3, 640, 640],
                'outputShape': [1, 7, 8400], 'classNames': model.names, 'opset': 12,
                'pcParityMaxAbsoluteErrors': parity, 'target': 'Jetson Nano / native TensorRT 8.0.1',
                'engineBuiltOnTarget': False}
    (OUTPUT / 'model.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
