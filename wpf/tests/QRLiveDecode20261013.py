import json
import sys
import cv2

# The decrypted fixture result arrives via stdin only; never print credentials.
raw = sys.stdin.read(1048577)
if len(raw) > 1048576:
    raise AssertionError('Oversized fixture result')
result = json.loads(raw)
links = result.get('connections', [])
if len(links) != 1:
    raise AssertionError('Expected one live OpenFlux connection')
decoded, points, _ = cv2.QRCodeDetector().detectAndDecode(cv2.imread(sys.argv[1]))
if points is None or decoded != links[0]:
    raise AssertionError('Live QR round-trip mismatch')
print('PASS live OpenFlux QR decodes to the exact server-issued connection')
