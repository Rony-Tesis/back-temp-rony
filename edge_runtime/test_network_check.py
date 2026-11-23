"""Connectivity and clock preparation must retain HTTPS and never control ROS."""
import json
from unittest import TestCase, main
from unittest.mock import MagicMock, patch

import network_check


class NetworkCheckTest(TestCase):
    def test_clock_preparation_updates_only_utc_and_ntp(self):
        stamp = '2023-01-01T00:00:00Z'
        with patch.object(network_check.os, 'geteuid', return_value=0, create=True), \
                patch.object(network_check.subprocess, 'run') as run:
            run.return_value.returncode = 0
            result = network_check.synchronize_clock(stamp)
            self.assertTrue(result['clockAdjusted'])
            self.assertTrue(result['ntpEnabled'])
            self.assertEqual(['date', '-u', '--set', stamp], run.call_args_list[0][0][0])
            self.assertEqual(['timedatectl', 'set-ntp', 'true'], run.call_args_list[1][0][0])

    def test_clock_change_requires_root_before_any_command(self):
        with patch.object(network_check.os, 'geteuid', return_value=1000, create=True), \
                patch.object(network_check.subprocess, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'sudo'):
                network_check.synchronize_clock('2023-01-01T00:00:00Z')
            run.assert_not_called()

    def test_rejects_plain_http_and_credentials_before_network_access(self):
        with patch.object(network_check.socket, 'getaddrinfo') as lookup:
            for url in ('http://example.invalid', 'https://test-user:test-password@example.invalid', 'https://example.invalid/private'):
                with self.assertRaises(ValueError):
                    network_check.check_api(url)
            lookup.assert_not_called()

    def test_dns_failure_is_reported_without_touching_services(self):
        with patch.object(network_check.socket, 'getaddrinfo', side_effect=network_check.socket.gaierror()), \
                patch.object(network_check.subprocess, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'WiFi'):
                network_check.check_api('https://rony-api.onrender.com')
            run.assert_not_called()

    def test_https_health_uses_verified_context_and_rejects_nonready_response(self):
        response = MagicMock()
        response.status = 200
        response.read.return_value = json.dumps({'status': 'up'}).encode()
        opener = MagicMock()
        opener.open.return_value.__enter__.return_value = response
        with patch.object(network_check.socket, 'getaddrinfo'), \
                patch.object(network_check.ssl, 'create_default_context', wraps=network_check.ssl.create_default_context) as context, \
                patch.object(network_check, 'build_opener', return_value=opener):
            network_check.check_api('https://rony-api.onrender.com')
            context.assert_called_once_with()
            self.assertEqual('https://rony-api.onrender.com/health', opener.open.call_args[0][0].full_url)
            response.read.return_value = b'{"status":"unavailable"}'
            with self.assertRaisesRegex(RuntimeError, 'lista'):
                network_check.check_api('https://rony-api.onrender.com')


if __name__ == '__main__':
    main()
