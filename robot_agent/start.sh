#!/usr/bin/env bash
set -euo pipefail
# systemd loads the same interactive bash environment used by the working notebook.
if [[ -n "${RONY_ROS_SETUP:-}" ]]; then
  source "$RONY_ROS_SETUP"
fi
python_bin="${RONY_ROS_PYTHON:-python3}"
"$python_bin" -c 'import rospy, rostopic, justkinematics; from jetmax_control.msg import SetJetMax, SetServo'
exec "$python_bin" -u /opt/rony-agent/agent.py \
  --profile /opt/rony-agent/rony_profile.json \
  --bind "${RONY_AGENT_BIND:-0.0.0.0}" --port 8090
