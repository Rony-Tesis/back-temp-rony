#!/usr/bin/env bash
set -euo pipefail
bundle_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
[[ "${1:-}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || {
  echo 'A trusted UTC timestamp from the installation PC is required.' >&2
  exit 1
}

# Clock and outbound HTTPS must work before stopping the previous executor.
# This preparation never imports ROS or publishes motor commands.
sudo python3 "$bundle_dir/network_check.py" --env-file "$bundle_dir/edge.env" --sync-clock "$1"
bash -ic "python3 \"$bundle_dir/run_edge.py\" --check --profile \"$bundle_dir/rony_profile.json\" --model-dir /home/hiwonder/rony-model-check"
sudo bash "$bundle_dir/install.sh"
systemctl is-active rony-edge
