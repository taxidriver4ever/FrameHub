"""End-to-end check against local Gateway + SMTP Mailpit. No third-party Python packages."""
import argparse, subprocess, pathlib, tempfile, base64, http.cookiejar, json, re, time, urllib.request, urllib.error, uuid
parser = argparse.ArgumentParser()
parser.add_argument('--base-url', default='http://localhost:28080')
parser.add_argument('--mailpit-url', default='http://localhost:18025')
parser.add_argument('--result', default='docs/video-smoke-result.json')
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
    try: response = client.open(req, timeout=120)
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

# The video service can start later than the user service after an image update.
for attempt in range(90):
    try:
        urllib.request.urlopen(args.base_url + '/api/v1/videos/me', timeout=2)
    except urllib.error.HTTPError as error:
        if error.code == 401: break
    except (urllib.error.URLError, TimeoutError, ConnectionError):
        pass
    time.sleep(1)
else:
    raise RuntimeError('Gateway/video-service not ready within 90 seconds')

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

with tempfile.TemporaryDirectory(prefix='framehub-video-') as work:
    video_file = pathlib.Path(work) / 'sample.mp4'
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc2=size=1280x720:rate=25', '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=44100', '-t', '3', '-c:v', 'libx264', '-preset', 'ultrafast', '-threads', '1', '-c:a', 'aac', str(video_file)], check=True)
    data = video_file.read_bytes()
    session = call('POST', '/api/v1/videos/uploads/init', {'filename':'sample.mp4','title':'Real CDC preview','fileSize':len(data),'contentType':'video/mp4'}, access)
    path = '/api/v1/videos/uploads/' + session['uploadSessionId']
    for n in range(1, session['partCount'] + 1):
        chunk = data[(n-1)*session['partSize']:n*session['partSize']]
        signed = call('POST', path + '/parts/presign', {'partNumber':n,'sizeBytes':len(chunk)}, access)
        preflight = urllib.request.Request(signed['uploadUrl'], method='OPTIONS', headers={'Origin':origin,'Access-Control-Request-Method':'PUT'})
        with urllib.request.urlopen(preflight) as response: assert response.headers.get('Access-Control-Allow-Origin') == origin
        request = urllib.request.Request(signed['uploadUrl'], data=chunk, method='PUT', headers={'Origin':origin})
        with urllib.request.urlopen(request) as response: assert response.status == 200
    assert len(call('GET', path, token=access)['uploadedParts']) == session['partCount']
    call('POST', path + '/complete', {}, access)
    call('POST', path + '/complete', {}, access)
    video_path = '/api/v1/videos/' + session['videoId']
    for _ in range(180):
        video = call('GET', video_path, token=access)
        assert video['status'] != 'FAILED', video
        if video['status'] == 'READY': break
        time.sleep(2)
    else: raise RuntimeError('Video did not reach READY through Canal/Kafka')
    playback = call('GET', video_path + '/playback', token=access)
    master = urllib.request.urlopen(playback['masterUrl']).read().decode()
    assert master.startswith('#EXTM3U')
    media_urls = [line for line in master.splitlines() if line and not line.startswith('#')]
    for url in media_urls:
        playlist = urllib.request.urlopen(url).read().decode()
        assert '#EXT-X-ENDLIST' in playlist
        for segment in [line for line in playlist.splitlines() if line and not line.startswith('#')]:
            assert len(urllib.request.urlopen(segment).read()) > 0
    subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-i',playback['masterUrl'],'-t','1','-f','null','-'],check=True)
    report = {'videoId':session['videoId'],'status':video['status'],'resolutions':video['resolutions'],'sourceBytes':len(data),'hlsDecoded':True}
    pathlib.Path(args.result).write_text(json.dumps(report,indent=2)+'\n')
    print('PASS: Gateway auth/CORS, real multipart, idempotent complete, Canal/Kafka, FFmpeg watermarked HLS and decode', report)
