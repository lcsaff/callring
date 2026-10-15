"""Generate the app's original short PCM melody. No external audio assets."""
from pathlib import Path
import math
import struct
import wave

target = Path(__file__).resolve().parents[1] / 'app/src/main/res/raw/short_melody.wav'
target.parent.mkdir(parents=True, exist_ok=True)
rate = 22050
frames = bytearray()
for hz, seconds in [(659.25, .22), (830.61, .22), (987.77, .42), (0, 1.0)]:
    count = int(rate * seconds)
    for i in range(count):
        t = i / rate
        envelope = min(1, i / (rate * .02), (count - i) / (rate * .09))
        value = .26 * envelope * (math.sin(2 * math.pi * hz * t) + .15 * math.sin(4 * math.pi * hz * t)) if hz else 0
        frames.extend(struct.pack('<h', int(value * 32767)))
with wave.open(str(target), 'wb') as output:
    output.setnchannels(1)
    output.setsampwidth(2)
    output.setframerate(rate)
    output.writeframes(frames)
print(f'Generated {target.name}: {len(frames) // 2 / rate:.2f}s')
