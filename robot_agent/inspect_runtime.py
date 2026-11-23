"""Read-only hardware/runtime inventory. Does not initialize ROS nodes or actuators."""
import glob
import importlib.util
import json
from pathlib import Path
import platform
import subprocess
import sys


def read(path):
    try:
        return Path(path).read_text().replace('\x00', '').strip()[:4096]
    except (OSError, UnicodeError):
        return None


def package(name):
    try:
        result = subprocess.run(['dpkg-query', '-W', '-f=${Version}', name],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
        return result.stdout.decode().strip() if result.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired):
        return None


def module(name):
    if importlib.util.find_spec(name) is None:
        return {'available': False}
    try:
        imported = __import__(name)
        value = {'available': True, 'version': str(getattr(imported, '__version__', 'unknown'))}
        if name == 'torch':
            value.update(cudaVersion=imported.version.cuda, cudaAvailable=imported.cuda.is_available())
        return value
    except Exception as error:
        return {'available': True, 'importError': str(error)[:200]}


if __name__ == '__main__':
    print(json.dumps({
        'model': read('/proc/device-tree/model'), 'machine': platform.machine(),
        'python': sys.version, 'osRelease': read('/etc/os-release'),
        'l4tRelease': read('/etc/nv_tegra_release'), 'memory': read('/proc/meminfo'),
        'packages': {name: package(name) for name in
                     ('nvidia-jetpack', 'nvidia-l4t-core', 'libnvinfer8', 'libnvinfer7', 'python3-libnvinfer')},
        'cudaVersion': read('/usr/local/cuda/version.json') or read('/usr/local/cuda/version.txt'),
        'modules': {name: module(name) for name in
                    ('numpy', 'cv2', 'tensorrt', 'pycuda', 'torch', 'onnxruntime', 'rospy', 'justkinematics')},
        'trtexec': glob.glob('/usr/src/tensorrt/bin/trtexec') + glob.glob('/usr/local/bin/trtexec'),
        'videoDevices': glob.glob('/dev/video*'), 'motionCommandsSent': 0,
        'rosParametersWritten': 0,
    }, ensure_ascii=False, indent=2))
