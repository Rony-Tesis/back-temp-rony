"""Executed on JetMax through a pinned SSH channel; JSON commands, no shell input."""
import fcntl
import json
import math
import os
import sys
import threading
import time

import rospy
import justkinematics
from jetmax_control.msg import JetMax, SetJetMax, SetServo

lock_file = open('/tmp/rony-physical.lock', 'a')
os.chmod('/tmp/rony-physical.lock', 0o600)
fcntl.flock(lock_file, fcntl.LOCK_EX | fcntl.LOCK_NB)
output_lock = threading.Lock()
latest = None
latest_at = 0.


def emit(value):
    with output_lock:
        sys.stdout.write(json.dumps(value, allow_nan=False) + '\n')
        sys.stdout.flush()


def observe(message):
    global latest, latest_at
    latest = {'x': message.x, 'y': message.y, 'z': message.z, 'gripperAngle': message.pwm1}
    latest_at = time.monotonic()
    emit({'kind': 'telemetry', 'pose': latest})


def check_position(position):
    if len(position) != 3 or not all(isinstance(v, (int, float)) and math.isfinite(v) for v in position):
        raise ValueError('Invalid position')
    x, y, z = position
    if not (-100 <= x <= 100 and -240 <= y <= -90 and 90 <= z <= 213):
        raise ValueError('Outside workspace')
    angles = justkinematics.inverse(16.8, tuple(position))
    pulses = justkinematics.deg_to_pulse(angles)
    if len(pulses) != 3 or not all(math.isfinite(v) and 0 <= v <= 1000 for v in pulses) or pulses[1] > 700 or pulses[2] < 470:
        raise ValueError('Unreachable or clamped servos')


rospy.init_node('rony_physical', anonymous=True, disable_signals=True)
subscriber = rospy.Subscriber('/jetmax/status', JetMax, observe, queue_size=1)
arm = rospy.Publisher('/jetmax/command', SetJetMax, queue_size=1)
gripper = rospy.Publisher('/jetmax/end_effector/servo1/command', SetServo, queue_size=1)

try:
    for line in sys.stdin:
        if len(line) > 8192:
            break
        request = json.loads(line)
        identity = request.get('id')
        try:
            if latest is None or time.monotonic() - latest_at > 2:
                raise ValueError('Stale ROS status')
            action = request['action']
            if action == 'preflight':
                expected = request['calibration']
                actual = rospy.get_param('/camera_cal/card_params')
                if any(actual.get(key) != expected[key] for key in ('K', 'R', 'T')):
                    raise ValueError('Calibration mismatch')
                deadline = time.monotonic() + 2
                while (not arm.get_num_connections() or not gripper.get_num_connections()) and time.monotonic() < deadline:
                    time.sleep(.05)
                if not arm.get_num_connections() or not gripper.get_num_connections():
                    raise ValueError('Controller unavailable')
                for position in request['positions']:
                    check_position(position)
            elif action in ('move', 'gripper'):
                expected = request['expected']
                if any(abs(latest[key] - expected[key]) > 2 for key in ('x', 'y', 'z', 'gripperAngle')):
                    raise ValueError('Unexpected external movement')
                duration = request['duration']
                if not isinstance(duration, (float, int)) or not math.isfinite(duration) or not 1 <= duration <= 3:
                    raise ValueError('Invalid duration')
                if action == 'move':
                    check_position(request['position'])
                    if not arm.get_num_connections():
                        raise ValueError('Controller unavailable')
                    x, y, z = request['position']
                    arm.publish(SetJetMax(x=x, y=y, z=z, duration=duration))
                else:
                    angle = request['angle']
                    if angle not in (0, 82) or not gripper.get_num_connections():
                        raise ValueError('Invalid gripper command')
                    gripper.publish(SetServo(data=angle, duration=duration))
            else:
                raise ValueError('Unknown action')
            emit({'kind': 'reply', 'id': identity, 'ok': True})
        except Exception as error:
            allowed = ('Stale ROS status', 'Calibration mismatch', 'Controller unavailable', 'Unreachable or clamped servos', 'Unexpected external movement', 'Outside workspace', 'Invalid duration', 'Invalid gripper command', 'Unknown action', 'Invalid position')
            reason = str(error) if str(error) in allowed else 'Invalid kinematics'
            emit({'kind': 'reply', 'id': identity, 'ok': False, 'reason': reason})
finally:
    rospy.signal_shutdown('SSH session closed')
    lock_file.close()
