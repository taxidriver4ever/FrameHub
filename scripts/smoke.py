"""End-to-end check against local Gateway + SMTP Mailpit. No third-party Python packages."""
import argparse, base64, http.cookiejar, json, re, time, urllib.request, urllib.error, uuid
parser = argparse.ArgumentParser()
parser.add_argument('--base-url', default='http://localhost:28080')
parser.add_argument('--mailpit-url', default='http://localhost:18025')
args = parser.parse_args()
origin = 'http://localhost:3000'
jar = http.cookiejar.CookieJar()
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))

def call(method, path, payload=None, token=None, expected=200, extra=None):
    headers = {'Origin': origin}
    if payload is not None: headers['Content-Type'] = 'application/json'
    if token: headers['Authorization'] = 'Bearer ' + token
    headers.update(extra or {})
    req = urllib.request.Request(args.base_url + path, data=json.dumps(payload).encode() if payload is not None else None, headers=headers, method=method)
    try: response = client.open(req, timeout=20)
    except urllib.error.HTTPError as error: response = error
    raw = response.read()
    assert response.status == expected, (method, path, response.status, raw.decode())
    assert response.headers.get('Access-Control-Allow-Origin') == origin
    if method == 'OPTIONS': return None
    return json.loads(raw)['data']

# Wait for the Gateway route and user-service security chain, not only the TCP listener.
for attempt in range(60):
    try:
        urllib.request.urlopen(args.base_url + '/users/me', timeout=2)
    except urllib.error.HTTPError as error:
        if error.code == 401: break
    except (urllib.error.URLError, TimeoutError, ConnectionError):
        pass
    time.sleep(1)
else:
    raise RuntimeError('Gateway/user-service not ready within 60 seconds')

call('OPTIONS', '/auth/register', extra={'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'content-type'})
email = 'smoke-' + str(uuid.uuid4()) + '@example.com'
password = 'SmokePassword123!'
call('POST', '/auth/email-code', {'email': email, 'scene': 'REGISTER'})
code = None
for _ in range(20):
    messages = json.load(urllib.request.urlopen(args.mailpit_url + '/api/v1/messages'))['messages']
    for message in messages:
        if any(item['Address'] == email for item in message['To']):
            detail = json.load(urllib.request.urlopen(args.mailpit_url + '/api/v1/message/' + message['ID']))
            code = re.search(r'\b[0-9]{6}\b', detail['Text']).group()
            break
    if code: break
    time.sleep(.5)
assert code, 'SMTP verification email was not received'
registered = call('POST', '/auth/register', {'email': email, 'password': password, 'verificationCode': code, 'nickname': 'Smoke creator'}, expected=201)
assert isinstance(registered['userId'], str)
logged = call('POST', '/auth/login', {'email': email, 'password': password})
access = logged['accessToken']
assert any(c.name == 'refresh_token' for c in jar)
assert call('GET', '/users/me', token=access)['email'] == email
call('PATCH', '/users/me', {'bio': 'Verified via Gateway'}, access)
creator = call('POST', '/creators/me/activate', {}, access)
assert call('POST', '/creators/me/activate', {}, access)['creatorId'] == creator['creatorId']
assert call('GET', '/creators/me', token=access)['creatorId'] == creator['creatorId']
public = call('GET', '/creators/' + creator['creatorId'])
assert 'email' not in public and 'status' not in public
png = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a3ioAAAAASUVORK5CYII=')
upload = call('POST', '/users/me/avatar/upload-url', {'filename': 'avatar.png', 'contentType': 'image/png', 'sizeBytes': len(png)}, access)
preflight = urllib.request.Request(upload['uploadUrl'], method='OPTIONS', headers={'Origin': origin, 'Access-Control-Request-Method': 'PUT', 'Access-Control-Request-Headers': 'content-type'})
with urllib.request.urlopen(preflight) as response:
    assert response.headers.get('Access-Control-Allow-Origin') == origin
req = urllib.request.Request(upload['uploadUrl'], data=png, method='PUT', headers={'Content-Type': 'image/png', 'Origin': origin})
assert urllib.request.urlopen(req).status == 200
call('PUT', '/users/me/avatar', {'objectKey': upload['objectKey']}, access)
refreshed = call('POST', '/auth/refresh')
call('POST', '/auth/logout', token=refreshed['accessToken'])
call('GET', '/users/me', token=refreshed['accessToken'], expected=401)
call('POST', '/auth/refresh', expected=401)
print('PASS: Gateway CORS, SMTP code, register, login cookie, profile, creator, MinIO direct upload, refresh, logout revocation')
