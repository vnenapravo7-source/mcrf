"""Explicitly supplied S3 credentials: bounded owned-object proof, DPAPI only."""
import ctypes
from ctypes import wintypes
import datetime as dt
import getpass
import hashlib
import hmac
import json
import pathlib
import re
import secrets
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET

ENDPOINT = 'https://hb.ru-msk.vkcloud-storage.ru'
BUCKET = 'outright'
REGION = 'ru-msk'

class Blob(ctypes.Structure):
    _fields_ = [('size', wintypes.DWORD), ('data', ctypes.POINTER(ctypes.c_ubyte))]

def protect(data):
    buf = ctypes.create_string_buffer(data)
    source = Blob(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_ubyte)))
    target = Blob()
    api = ctypes.windll.crypt32.CryptProtectData
    api.argtypes = [ctypes.POINTER(Blob), wintypes.LPCWSTR, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
    api.restype = wintypes.BOOL
    if not api(ctypes.byref(source), 'MCRF owned S3', None, None, None, 1, ctypes.byref(target)):
        raise ctypes.WinError()
    try:
        return ctypes.string_at(target.data, target.size)
    finally:
        ctypes.windll.kernel32.LocalFree(ctypes.cast(target.data, ctypes.c_void_p))
        ctypes.memset(buf, 0, len(data))

access = getpass.getpass('S3 Access Key ID (not echoed): ')
secret = getpass.getpass('S3 Secret Key (not echoed): ')
if not access or not secret or any(ord(x) < 33 or ord(x) > 126 for x in access + secret):
    raise SystemExit('Credential input rejected; no request sent')
operation = uuid.uuid4().hex
prefix = 'mcrf-p0/' + operation + '/'
object_name = prefix + 'verification-' + uuid.uuid4().hex + '.bin'
receipt = {'bucket': BUCKET, 'endpoint': ENDPOINT, 'region': REGION, 'operation': operation,
           'state': 'STARTED', 'requests': [], 'bucketPolicyOrLifecycleChanged': False,
           'otherObjectsTouched': False, 'credentialStorage': 'Windows DPAPI CurrentUser only'}
private = pathlib.Path('D:/MCRF/Private') / ('s3-p0-' + operation)
private.mkdir()

def request(method, key='', query=None, payload=b''):
    query = query or {}
    now = dt.datetime.now(dt.timezone.utc)
    stamp, day = now.strftime('%Y%m%dT%H%M%SZ'), now.strftime('%Y%m%d')
    path = urllib.parse.quote('/' + BUCKET + ('/' + key if key else ''), safe='/~')
    encoded = '&'.join(urllib.parse.quote(k, safe='~') + '=' + urllib.parse.quote(v, safe='~') for k, v in sorted(query.items()))
    host = urllib.parse.urlparse(ENDPOINT).netloc
    digest = hashlib.sha256(payload).hexdigest()
    headers = 'host:' + host + '\nx-amz-content-sha256:' + digest + '\nx-amz-date:' + stamp + '\n'
    names = 'host;x-amz-content-sha256;x-amz-date'
    canonical = '\n'.join((method, path, encoded, headers, names, digest))
    scope = day + '/' + REGION + '/s3/aws4_request'
    value = '\n'.join(('AWS4-HMAC-SHA256', stamp, scope, hashlib.sha256(canonical.encode()).hexdigest()))
    signing = ('AWS4' + secret).encode()
    for part in (day, REGION, 's3', 'aws4_request'):
        signing = hmac.new(signing, part.encode(), hashlib.sha256).digest()
    signature = hmac.new(signing, value.encode(), hashlib.sha256).hexdigest()
    authorization = 'AWS4-HMAC-SHA256 Credential=' + access + '/' + scope + ', SignedHeaders=' + names + ', Signature=' + signature
    url = ENDPOINT + path + ('?' + encoded if encoded else '')
    req = urllib.request.Request(url, method=method, data=payload if method == 'PUT' else None,
        headers={'Host': host, 'X-Amz-Date': stamp, 'X-Amz-Content-Sha256': digest, 'Authorization': authorization})
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            code, body = response.status, response.read(131072)
    except urllib.error.HTTPError as error:
        code, body = error.code, error.read(8192)
    except Exception as error:
        receipt['requests'].append({'method': method, 'transportError': type(error).__name__})
        raise RuntimeError('S3 transport failure; no raw credentials or response printed') from None
    row = {'method': method, 'queryNames': list(query), 'httpStatus': code, 'ownObject': bool(key)}
    if code >= 400:
        try:
            value = ET.fromstring(body).findtext('Code') or ''
            if re.fullmatch('[A-Za-z0-9_]{1,80}', value): row['providerCode'] = value
        except ET.ParseError:
            pass
    receipt['requests'].append(row)
    print('S3_REQUEST=' + json.dumps(row), flush=True)
    return code, body

created = False
try:
    code, _ = request('HEAD')
    if code != 200: raise RuntimeError('Bucket HEAD rejected; no mutation attempted')
    code, location = request('GET', query={'location': ''})
    if code != 200: raise RuntimeError('Bucket region lookup rejected; no mutation attempted')
    root = ET.fromstring(location)
    discovered = (root.text or '').strip() or REGION
    if discovered != REGION:
        raise RuntimeError('Provider region differs; review before signing a write')
    payload = secrets.token_bytes(1024)
    code, _ = request('PUT', object_name, payload=payload)
    if code not in (200, 201): raise RuntimeError('Owned object PUT rejected')
    created = True
    code, received = request('GET', object_name)
    if code != 200 or received != payload: raise RuntimeError('Owned payload mismatch')
    code, listed = request('GET', query={'list-type': '2', 'prefix': prefix, 'max-keys': '5'})
    if code != 200 or object_name.encode() not in listed: raise RuntimeError('Owned prefix LIST proof missing')
    code, _ = request('DELETE', object_name)
    if code not in (200, 204): raise RuntimeError('Owned object DELETE rejected')
    created = False
    tuning = {'pollIntervalMs': 1000, 'writeIntervalMs': 50, 'idleTimeoutSec': 60, 'maxFileSizeBytes': 65536}
    storage = {'type': 's3', 'bucket': BUCKET, 'endpoint': ENDPOINT, 'region': REGION,
               'prefix': prefix, 'sessionsDir': 'sessions', 'accessKey': access, 'secretKey': secret}
    client_id = 'mcrf-' + operation[:16]
    profile = {'type': 's3bypass', 'name': 'S3 Bypass VK outright', 'settings': {'storage': dict(storage, prefix=prefix + client_id + '/'), 'tuning': tuning}}
    server = {'log': {'loglevel': 'none'}, 'inbounds': [{'tag': 'owned-s3', 'protocol': 'fedarisha', 'settings': {
        'storage': storage, 'tuning': tuning, 'clients': [{'id': client_id}], 'webhook': {'enabled': False, 'autoSetup': False}}}],
        'outbounds': [{'tag': 'direct', 'protocol': 'freedom'}]}
    protected = protect(json.dumps({'profile': profile, 'server': server}).encode())
    (private / 'credentials.dpapi').write_bytes(protected)
    receipt.update(state='PASS', payloadBytes=1024, payloadSha256=hashlib.sha256(payload).hexdigest(),
                   prefix=prefix, protectedCredentialFile=str(private / 'credentials.dpapi'))
except Exception as error:
    receipt.update(state='FAILED_PRESERVED', error=str(error) if isinstance(error, RuntimeError) else type(error).__name__)
finally:
    if created:
        try:
            code, _ = request('DELETE', object_name)
            receipt['ownedObjectCleanupSucceeded'] = code in (200, 204)
        except Exception:
            receipt['ownedObjectCleanupSucceeded'] = False
    access = secret = None
    (private / 'receipt.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')
    print('S3_PREPARE=' + receipt['state'] + ';PRIVATE_STAGE=' + str(private), flush=True)
if receipt['state'] != 'PASS': sys.exit(1)
