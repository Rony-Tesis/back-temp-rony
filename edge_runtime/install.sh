#!/usr/bin/env bash
set -euo pipefail
[[ "$EUID" -eq 0 ]] || { echo 'Run sudo bash install.sh' >&2; exit 1; }
bundle_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
model_dir=/home/hiwonder/rony-model-check
test -s "$bundle_dir/edge.env"
test -s "$model_dir/engine.json"
test -s "$model_dir/yolo11n.engine"
python3 - "$bundle_dir/edge.env" "$model_dir/engine.json" "$model_dir/yolo11n.engine" <<'PY'
import hashlib, json, pathlib, sys
from urllib.parse import urlparse
values = dict(line.split('=', 1) for line in pathlib.Path(sys.argv[1]).read_text().splitlines() if line)
parsed = urlparse(values.get('RONY_API_URL', ''))
assert parsed.scheme == 'https' and parsed.hostname and not parsed.hostname.endswith('.invalid')
assert not parsed.username and not parsed.password and not parsed.query and not parsed.fragment
assert 32 <= len(values.get('RONY_DEVICE_TOKEN', '')) <= 256
receipt = json.loads(pathlib.Path(sys.argv[2]).read_text())
assert receipt['engineBuiltOnTarget']
assert receipt['engineSha256'] == hashlib.sha256(pathlib.Path(sys.argv[3]).read_bytes()).hexdigest()
PY
# Only our services are updated. Vendor ROS, limits and calibration remain untouched.
previous_agent=false
if systemctl is-active --quiet rony-agent.service; then previous_agent=true; fi
restore_previous() {
  systemctl stop rony-edge.service 2>/dev/null || true
  systemctl disable rony-edge.service 2>/dev/null || true
  if "$previous_agent"; then systemctl start rony-agent.service || true; fi
  echo 'Edge startup failed. Previous bench executor restored when it was active.' >&2
}
trap restore_previous ERR
systemctl stop rony-edge.service 2>/dev/null || true
systemctl stop rony-agent.service 2>/dev/null || true
install -d -m 755 /opt/rony-edge/{robot_agent,adapters,model,vendor}
install -d -m 750 -o root -g hiwonder /etc/rony-edge
install -d -m 700 -o hiwonder -g hiwonder /var/lib/rony-edge
install -m 644 "$bundle_dir/"*.py "$bundle_dir/rony_profile.json" /opt/rony-edge/
install -m 644 "$bundle_dir/robot_agent/agent.py" /opt/rony-edge/robot_agent/
install -m 644 "$bundle_dir/adapters/"*.py /opt/rony-edge/adapters/
install -m 644 "$bundle_dir/vendor/"*.whl /opt/rony-edge/vendor/
install -m 644 "$model_dir/yolo11n.engine" "$model_dir/engine.json" /opt/rony-edge/model/
install -m 755 "$bundle_dir/start.sh" /opt/rony-edge/start.sh
install -m 600 -o root -g root "$bundle_dir/edge.env" /etc/rony-edge/edge.env
install -m 644 "$bundle_dir/rony-edge.service" /etc/systemd/system/rony-edge.service
systemctl daemon-reload
# Installation/activation is an explicit start. Keep future boots free for other robot work.
systemctl disable rony-edge.service
systemctl start rony-edge.service
sleep 2
if ! systemctl is-active --quiet rony-edge.service; then
  restore_previous
  echo 'Edge startup failed. Previous executor restored; inspect journalctl -u rony-edge.' >&2
  exit 1
fi
trap - ERR
systemctl disable rony-agent.service 2>/dev/null || true
echo 'Runtime started for this installation; boot autostart is disabled.'
echo 'Start manually: sudo systemctl start rony-edge.service'
echo 'Stop and release YOLO resources: sudo systemctl stop rony-edge.service'
echo 'Starting the service only observes; enable classification from the HTTPS web/API.'
