"""Readiness regression: read robot calibration and validate without submitting a plan."""
import copy
import hashlib
import io
import json
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

from check_ready import YOLO_ROOT, agent_request, motion_preflight


class ReadinessTest(unittest.TestCase):
    def test_reads_robot_values_and_only_posts_nonmoving_preflight(self):
        raw = (YOLO_ROOT / 'runtime/rony_profile.json').read_bytes().replace(b'\r\n', b'\n')
        profile = json.loads(raw)
        profile_sha = hashlib.sha256(raw).hexdigest()
        calibration = copy.deepcopy(profile['calibration'])
        calibration['T'][0][0] += 3
        calibration_sha = hashlib.sha256(json.dumps(calibration, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        packet = {'schemaVersion': 2, 'testMode': False, 'profileSha256': profile_sha,
                  'motionPolicy': 'vertical-home-before-disposal-v1',
                  'parameters': calibration, 'sha256': calibration_sha,
                  'source': 'ros:/camera_cal/card_params'}
        with patch('check_ready.agent_request', side_effect=[packet, {'ok': True}]) as request:
            result = motion_preflight('http://test', 'private', profile, profile_sha)
        self.assertEqual(0, result['motionCommandsSent'])
        self.assertEqual(6, result['positionsChecked'])
        self.assertEqual(['/v1/calibration', '/v1/preflight'], [c.args[2] for c in request.call_args_list])
        self.assertEqual(calibration_sha, request.call_args_list[1].args[3]['calibrationSha256'])

    def test_preserves_agent_rejection_reason(self):
        error = HTTPError('http://test', 409, 'Conflict', {},
                          io.BytesIO(json.dumps({'detail': 'Calibración vigente no disponible'}).encode()))
        with patch('check_ready.urlopen', side_effect=error):
            with self.assertRaisesRegex(RuntimeError, '409: Calibración vigente no disponible'):
                agent_request('http://test', 'private', '/v1/preflight', {})

    def test_old_agent_cannot_pass_readiness_without_home_clearance_policy(self):
        raw = (YOLO_ROOT / 'runtime/rony_profile.json').read_bytes().replace(b'\r\n', b'\n')
        profile = json.loads(raw)
        digest = hashlib.sha256(raw).hexdigest()
        packet = {'schemaVersion': 2, 'profileSha256': digest, 'testMode': False}
        with patch('check_ready.agent_request', return_value=packet) as request:
            with self.assertRaisesRegex(RuntimeError, 'exigir elevación vertical y HOME'):
                motion_preflight('http://test', 'private', profile, digest)
        self.assertEqual(1, request.call_count)


if __name__ == '__main__':
    unittest.main()
