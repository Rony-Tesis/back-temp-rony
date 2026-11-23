"""Compile ONNX with the JetMax's existing TensorRT. No ROS imports or parameter writes."""
import argparse
import hashlib
import gc
import json
from pathlib import Path
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model-dir', required=True)
    parser.add_argument('--workspace-mb', type=int, default=128)
    args = parser.parse_args()
    if not 32 <= args.workspace_mb <= 512:
        raise SystemExit('Workspace must be 32..512 MiB')
    import tensorrt as trt
    directory = Path(args.model_dir)
    manifest = json.loads((directory / 'model.json').read_text())
    raw = (directory / 'yolo11n.onnx').read_bytes()
    assert hashlib.sha256(raw).hexdigest() == manifest['onnxSha256'], 'ONNX checksum mismatch'
    receipt = directory / 'engine.json'
    existing = directory / 'yolo11n.engine'
    if receipt.is_file() and existing.is_file():
        previous = json.loads(receipt.read_text())
        if (previous.get('onnxSha256') == manifest['onnxSha256']
                and previous.get('tensorRtVersion') == trt.__version__
                and previous.get('engineSha256') == hashlib.sha256(existing.read_bytes()).hexdigest()):
            print(json.dumps({'ok': True, 'reusedExistingEngine': True, 'motionCommandsSent': 0}))
            return
    import pycuda.driver as cuda
    cuda.init()
    cuda_context = cuda.Device(0).make_context()
    logger = trt.Logger(trt.Logger.WARNING)
    builder = trt.Builder(logger)
    network = builder.create_network(1 << int(trt.NetworkDefinitionCreationFlag.EXPLICIT_BATCH))
    onnx_parser = trt.OnnxParser(network, logger)
    if not onnx_parser.parse(raw):
        errors = [str(onnx_parser.get_error(i)) for i in range(onnx_parser.num_errors)]
        raise RuntimeError('TensorRT rejected the ONNX graph: ' + '\n'.join(errors))
    assert list(network.get_input(0).shape) == manifest['inputShape']
    config = builder.create_builder_config()
    config.max_workspace_size = args.workspace_mb * 1024 * 1024
    if builder.platform_has_fast_fp16:
        config.set_flag(trt.BuilderFlag.FP16)
    started = time.monotonic()
    engine = builder.build_engine(network, config)
    if engine is None:
        raise RuntimeError('TensorRT could not build the engine; retain the existing robot services')
    blob = bytes(engine.serialize())
    temporary = directory / 'yolo11n.engine.tmp'
    temporary.write_bytes(blob)
    temporary.replace(directory / 'yolo11n.engine')
    result = dict(manifest, engineBuiltOnTarget=True, tensorRtVersion=trt.__version__,
                  engineSha256=hashlib.sha256(blob).hexdigest(), buildSeconds=round(time.monotonic() - started, 2))
    (directory / 'engine.json').write_text(json.dumps(result, indent=2) + '\n')
    # TensorRT allocations must be destroyed while their CUDA context is still current.
    engine = None
    config = None
    onnx_parser = None
    network = None
    builder = None
    gc.collect()
    cuda_context.pop()
    cuda_context.detach()
    print(json.dumps({'ok': True, 'tensorRtVersion': trt.__version__,
                      'buildSeconds': result['buildSeconds'], 'motionCommandsSent': 0}, indent=2))


if __name__ == '__main__':
    main()
