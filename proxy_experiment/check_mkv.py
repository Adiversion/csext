import sys, re
sys.stdout.reconfigure(encoding='utf-8')

with open('proxy_experiment/nagabandham_start_30s.mkv', 'rb') as f:
    data = f.read(1024 * 1024)

print("Data read:", len(data))
if data.startswith(b'\x1a\x45\xdf\xa3'):
    print("[EBML] Valid Matroska (MKV) header detected!")
else:
    print("[EBML] First bytes hex:", data[:16].hex())

text = data[:65536].decode('latin1', errors='ignore')
print("Contains 'V_AV1'?", "V_AV1" in text)
print("Contains 'A_EAC3'?", "A_EAC3" in text or "EAC3" in text or "A_AC3" in text)
print("Contains 'Kannada'?", "Kannada" in text or "kan" in text)

for m in re.finditer(r'([A-Za-z0-9_-]*(?:AV1|EAC3|1080|1920|Kannada|Telugu)[A-Za-z0-9_-]*)', text):
    print("Detected Track / Stream Tag:", m.group(1))
