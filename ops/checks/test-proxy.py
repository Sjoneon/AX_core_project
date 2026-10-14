#!/usr/bin/env python3
"""Exercise HTTPS, redirects, CORS, the /api/tools block and rate limits using disposable local Nginx containers."""
import http.client
import argparse
import json
import ssl
import subprocess
import tempfile
import time
import uuid
from pathlib import Path

ORIGIN = 'https://homecare-omega-eight.vercel.app'


def docker(*args):
    result = subprocess.run(['docker', *args], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(result.stderr)
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', default='homecare-nginx:stage6')
    args = parser.parse_args()
    network = 'homecare-proxy-test-' + uuid.uuid4().hex[:10]
    backend, proxy = network + '-backend', network + '-nginx'
    with tempfile.TemporaryDirectory(prefix='homecare-proxy-test-') as work:
        root = Path(work)
        certificates = root / 'live/homecare-api'
        certificates.mkdir(parents=True)
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                        '-keyout', str(certificates / 'privkey.pem'), '-out', str(certificates / 'fullchain.pem'),
                        '-subj', '/CN=local-test', '-addext', 'subjectAltName=IP:127.0.0.1,IP:43.202.80.116'],
                       check=True, capture_output=True)
        mock = root / 'backend.conf'
        mock.write_text('server { listen 8080; if ($host ~ _) { return 400; } location /api/ { return 401; } '
                        'location = /actuator/health { default_type application/json; '
                        'return 200 \'{"status":"UP"}\'; } }\n')
        context = ssl.create_default_context(cafile=str(certificates / 'fullchain.pem'))
        try:
            docker('network', 'create', network)
            docker('run', '-d', '--name', backend, '--network', network, '--network-alias', 'backend',
                   '--platform', 'linux/amd64', '-v', str(mock) + ':/etc/nginx/conf.d/default.conf:ro',
                   'nginx:1.30.5-alpine@sha256:0985e772fb9f729e6fa0980da05fca5d9c468e870eed43071545afa9d2e27d94')
            docker('run', '-d', '--name', proxy, '--network', network, '--platform', 'linux/amd64',
                   '-p', '127.0.0.1:18443:443', '-p', '127.0.0.1:18080:80',
                   '-v', str(root) + ':/etc/letsencrypt:ro', args.image)
            for _ in range(30):
                try:
                    connection = http.client.HTTPSConnection('127.0.0.1', 18443, context=context, timeout=3)
                    connection.request('GET', '/actuator/health')
                    response = connection.getresponse()
                    assert response.status == 200 and json.loads(response.read())['status'] == 'UP'
                    connection.close()
                    break
                except (OSError, http.client.HTTPException):
                    time.sleep(1)
            else:
                raise RuntimeError(docker('logs', proxy))
            health = docker('exec', proxy, 'wget', '-qO-', 'http://127.0.0.1/internal-health')
            assert json.loads(health)['status'] == 'UP'
            cases = [
                ('OPTIONS', '/api/chat', {'Origin': ORIGIN, 'Access-Control-Request-Method': 'POST',
                                         'Access-Control-Request-Headers': 'authorization,content-type'}, 204, True),
                ('GET', '/api/conversations', {'Origin': ORIGIN}, 401, True),
                ('OPTIONS', '/api/chat', {'Origin': 'https://evil.example', 'Access-Control-Request-Method': 'POST'}, 403, False),
                ('OPTIONS', '/api/chat', {'Origin': ORIGIN + '.evil.example', 'Access-Control-Request-Method': 'POST'}, 403, False),
                ('OPTIONS', '/api/chat', {'Origin': 'null', 'Access-Control-Request-Method': 'POST'}, 403, False),
                ('OPTIONS', '/api/chat', {'Origin': ORIGIN, 'Access-Control-Request-Method': 'PUT'}, 403, True),
                ('GET', '/api/conversations', {}, 401, False),
            ]
            for method, path, headers, status, allowed in cases:
                connection = http.client.HTTPSConnection('127.0.0.1', 18443, context=context, timeout=5)
                connection.request(method, path, headers=headers)
                response = connection.getresponse()
                assert response.status == status
                assert response.getheader('Access-Control-Allow-Origin') == (ORIGIN if allowed else None)
                assert 'Origin' in response.getheader('Vary', '')
                if status == 204:
                    assert response.read() == b''
                    assert 'Authorization' in response.getheader('Access-Control-Allow-Headers')
                connection.close()
            # The internal tool API is never reachable from the internet, however the path is spelled.
            for path in ('/api/tools/search_knowledge', '/api/tools', '/api//tools/lookup_model', '/api/%74ools/lookup_model'):
                connection = http.client.HTTPSConnection('127.0.0.1', 18443, context=context, timeout=5)
                connection.request('POST', path, body='{}', headers={'Origin': ORIGIN, 'Content-Type': 'application/json'})
                response = connection.getresponse()
                assert response.status == 404, (path, response.status)
                response.read()
                connection.close()
            # Per-IP limits: POST /api/chat allows a burst of 10 after the first request, /api/auth/ a burst of 5.
            for path, allowed in (('/api/chat', 11), ('/api/auth/login', 6)):
                statuses = []
                for _ in range(allowed + 4):
                    connection = http.client.HTTPSConnection('127.0.0.1', 18443, context=context, timeout=5)
                    connection.request('POST', path, body='{}', headers={'Origin': ORIGIN, 'Content-Type': 'application/json'})
                    response = connection.getresponse()
                    statuses.append(response.status)
                    if response.status == 429:
                        # The browser can still read the rejection because CORS headers are added "always".
                        assert response.getheader('Access-Control-Allow-Origin') == ORIGIN
                    response.read()
                    connection.close()
                assert statuses[:allowed] == [401] * allowed and 429 in statuses[allowed:], (path, statuses)
            connection = http.client.HTTPConnection('127.0.0.1', 18080, timeout=5)
            connection.request('GET', '/api/conversations?test=1')
            response = connection.getresponse()
            assert response.status == 308
            assert response.getheader('Location') == 'https://43.202.80.116/api/conversations?test=1'
            connection.close()
            print('PASS: trusted local TLS, health, HTTPS redirect, seven CORS/preflight/error cases, '
                  'blocked /api/tools spellings and per-IP chat/auth rate limits.')
        finally:
            for name in (proxy, backend):
                subprocess.run(['docker', 'rm', '-f', name], capture_output=True)
            subprocess.run(['docker', 'network', 'rm', network], capture_output=True)


if __name__ == '__main__':
    main()
