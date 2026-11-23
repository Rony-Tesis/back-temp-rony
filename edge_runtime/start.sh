#!/usr/bin/env bash
set -euo pipefail
if [[ -n "${RONY_ROS_SETUP:-}" ]]; then
  source "$RONY_ROS_SETUP"
fi
exec "${RONY_ROS_PYTHON:-python3}" -u /opt/rony-edge/run_edge.py \
  --profile /opt/rony-edge/rony_profile.json \
  --model-dir /opt/rony-edge/model --state-dir /var/lib/rony-edge
