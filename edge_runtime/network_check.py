"""Prepare UTC and verify the public API before replacing our robot service."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import socket
import ssl
import subprocess
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, build_opener, HTTPRedirectHandler, HTTPSHandler


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def synchronize_clock(utc_text):
    expected = datetime.strptime(utc_text, '%Y-%m-%dT%H:%M:%SZ').replace(tzinfo=timezone.utc)
    delta = abs((datetime.now(timezone.utc) - expected).total_seconds())
    if os.geteuid() != 0:
        raise RuntimeError('Ejecuta esta preparación con sudo para sincronizar la hora HTTPS')
    adjusted = delta > 300
    if adjusted:
        subprocess.run(['date', '-u', '--set', utc_text], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        ntp = subprocess.run(['timedatectl', 'set-ntp', 'true'], timeout=8,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        ntp = False
    return {'clockAdjusted': adjusted, 'ntpEnabled': ntp}


def check_api(api):
    parsed = urlparse(api)
    if (parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment or parsed.path not in ('', '/')):
        raise ValueError('La API requiere un origen HTTPS sin credenciales')
    try:
        socket.getaddrinfo(parsed.hostname, parsed.port or 443, type=socket.SOCK_STREAM)
    except socket.gaierror:
        raise RuntimeError('El JetMax no resuelve la API. Conéctalo a una WiFi con internet y revisa su DNS')
    opener = build_opener(NoRedirect(), HTTPSHandler(context=ssl.create_default_context()))
    try:
        with opener.open(Request(api.rstrip('/') + '/health'), timeout=15) as response:
            if response.status != 200 or json.loads(response.read(4096).decode('utf-8')).get('status') != 'up':
                raise RuntimeError('La API todavía no está lista; revisa /health en Render')
    except HTTPError as error:
        raise RuntimeError('La API no está lista: HTTP ' + str(error.code))
    except URLError as error:
        if isinstance(error.reason, ssl.SSLError):
            raise RuntimeError('No se pudo verificar TLS. Revisa fecha UTC y certificados CA de Ubuntu; no desactives SSL')
        raise RuntimeError('El JetMax no alcanza la API por HTTPS; revisa WiFi, gateway e internet')
    except (socket.timeout, TimeoutError):
        raise RuntimeError('La API no respondió a tiempo. Abre /health, espera el arranque de Render y repite')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', default='edge.env')
    parser.add_argument('--sync-clock')
    args = parser.parse_args()
    values = dict(line.split('=', 1) for line in Path(args.env_file).read_text().splitlines()
                  if line and not line.startswith('#') and '=' in line)
    clock = synchronize_clock(args.sync_clock) if args.sync_clock else {'clockAdjusted': False}
    check_api(values.get('RONY_API_URL', ''))
    print(json.dumps(dict(clock, ok=True, apiHttpsReady=True,
                          motionCommandsSent=0, rosParametersWritten=0), indent=2))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
