"""Generate four original, seamless mono PCM ringtone loops. No external assets."""
from pathlib import Path
import math
import struct
import wave

RATE = 22050
OUT = Path(__file__).resolve().parents[2] / "app/src/main/res/raw"


def render(name, duration, notes, voice):
    samples = [0.0] * int(duration * RATE)
    for start, length, frequencies in notes:
        for i in range(int(length * RATE)):
            t = i / RATE
            attack = min(1.0, t / 0.012)
            release = min(1.0, (length - t) / 0.08)
            envelope = attack * release
            if voice == "bell":
                envelope *= math.exp(-4.0 * t)
            elif voice == "soft":
                envelope *= math.exp(-1.8 * t)
            value = 0.0
            for frequency in frequencies:
                phase = 2 * math.pi * frequency * t
                value += math.sin(phase)
                if voice == "bell":
                    value += 0.22 * math.sin(2.76 * phase) * math.exp(-5 * t)
                elif voice == "soft":
                    value += 0.12 * math.sin(2 * phase)
            index = int(start * RATE) + i
            if index < len(samples):
                samples[index] += envelope * value / len(frequencies)
    peak = max(abs(sample) for sample in samples)
    pcm = b"".join(struct.pack("<h", round(sample / peak * 21000)) for sample in samples)
    with wave.open(str(OUT / f"ring_{name}.wav"), "wb") as stream:
        stream.setparams((1, 2, RATE, 0, "NONE", "not compressed"))
        stream.writeframes(pcm)


def main():
    render("chime", 4.8, [(0, 1.1, [1046.50]), (0.3, 1.1, [1318.51]),
                           (0.6, 1.6, [1567.98]), (2.4, 1.6, [1318.51, 1567.98])], "bell")
    render("gentle", 6.0, [(0, 1.8, [523.25, 659.25]), (1.0, 1.8, [659.25, 783.99]),
                            (2.0, 2.5, [523.25, 783.99])], "soft")
    render("classic", 4.0, [(start, 0.09, [440, 480]) for offset in [0, 1.0]
                            for start in [offset + n * 0.12 for n in range(5)]], "phone")
    render("digital", 3.6, [(start, 0.16, [frequency]) for start, frequency in
                             [(0, 880), (0.22, 1174.66), (0.44, 1396.91),
                              (1.5, 880), (1.72, 1174.66), (1.94, 1396.91)]], "digital")


if __name__ == "__main__":
    main()
