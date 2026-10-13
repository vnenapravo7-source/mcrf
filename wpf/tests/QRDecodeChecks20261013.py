import pathlib
import sys
import cv2

root = pathlib.Path(sys.argv[1])
detector = cv2.QRCodeDetector()
count = 0
for image in sorted(root.glob('qr-*.png')):
    pixels = cv2.imread(str(image))
    decoded, points, _ = detector.detectAndDecode(pixels)
    expected = image.with_suffix('.txt').read_text(encoding='utf-8')
    if points is None or decoded != expected:
        raise AssertionError('QR round-trip mismatch: ' + image.name)
    count += 1
    print('PASS', image.name, 'decoded exactly')
assert count == 7, count
print('OK', count, 'independent QR decoding checks')
