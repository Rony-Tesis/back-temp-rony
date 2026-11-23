"""No robot/ROS connection: check HTTP reuse, bounded pacing and unchanged control lease."""
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
from pathlib import Path
import ssl
import sys
import threading
import unittest
from unittest.mock import MagicMock

sys.path.insert(0, str(Path(__file__).resolve().parent))
from run_edge import CloudClient


class CloudClientTest(unittest.TestCase):
    def controller(self):
        controller = MagicMock()
        controller.packet.side_effect = lambda device, sequence: {'deviceId': device, 'sequence': sequence}
        controller.vision.latest.return_value = (1, None, 0, None)
        return controller

    def test_frames_reuse_http_connection_and_obey_server_interval_without_motion(self):
        addresses = []
        class Handler(BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.1'
            def do_POST(self):
                json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                addresses.append(self.client_address)
                reply = json.dumps({'controlLeaseSeconds': 15, 'commands': [],
                                    'acknowledgedOperations': [], 'pollIntervalMs': 200}).encode()
                self.send_response(200)
                self.send_header('Content-Length', str(len(reply)))
                self.end_headers()
                self.wfile.write(reply)
            def log_message(self, *_): pass
        server = HTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        controller = self.controller()
        client = CloudClient('http://127.0.0.1:' + str(server.server_port), 'x' * 64,
                             'test-device', controller, allow_test_http=True)
        try:
            client.exchange_once()
            client.exchange_once()
            self.assertEqual(2, len(addresses))
            self.assertEqual(addresses[0], addresses[1])
            self.assertEqual(.2, client.poll_seconds)
            self.assertTrue(client.healthy())
            controller.enqueue.assert_not_called()
        finally:
            client.connection.close()
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_public_connection_keeps_certificate_and_hostname_verification(self):
        client = CloudClient('https://camera-api.example.invalid', 'x' * 64, 'test-device', self.controller())
        self.assertEqual(ssl.CERT_REQUIRED, client.connection._context.verify_mode)
        self.assertTrue(client.connection._context.check_hostname)
        self.assertFalse(client.healthy())
        client.connection.close()


if __name__ == '__main__':
    unittest.main()
