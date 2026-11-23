"""Bundle a hash-pinned, pure-Python 3.6 WebSocket client; never install into system Python."""
import hashlib
import json
from pathlib import Path
import sys
from urllib.parse import urlsplit
from urllib.request import urlopen

NAME = 'websocket_client-1.2.3-py3-none-any.whl'
SHA256 = '2eed4cc58e4d65613ed6114af2f380f7910ff416fc8c46947f6e76b6815f56c0'


def prepare(directory):
    path = Path(directory) / NAME
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == SHA256:
        return
    with urlopen('https://pypi.org/pypi/websocket-client/1.2.3/json', timeout=20) as response:
        metadata = json.load(response)
    release = next(item for item in metadata['urls'] if item['filename'] == NAME)
    url = urlsplit(release['url'])
    if url.scheme != 'https' or url.hostname != 'files.pythonhosted.org':
        raise ValueError('Unexpected video dependency source')
    with urlopen(release['url'], timeout=30) as response:
        wheel = response.read(100001)
    if hashlib.sha256(wheel).hexdigest() != SHA256:
        raise ValueError('Video dependency checksum mismatch')
    path.write_bytes(wheel)


if __name__ == '__main__':
    prepare(sys.argv[1])
    print('Isolated Python 3.6 video dependency verified (no system packages changed).')
