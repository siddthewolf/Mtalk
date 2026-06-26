#!/usr/bin/env python3
"""Generate a seamless CC0 ambient background-music loop for Relic Rush 3D.

No external deps (pure stdlib). Renders a gentle 4-chord pad + arpeggio in
A-minor, 16 s long, 22050 Hz mono 16-bit WAV, with an equal-power crossfade so
the end splices perfectly back into the start.

The result is an original composition created for this project -> CC0.
"""
import math, struct, wave, os

SR = 22050
BPM = 92.0
BEAT = 60.0 / BPM            # seconds per beat
CHORD_BEATS = 4              # one chord per bar
BARS = 4
LOOP_LEN = BARS * CHORD_BEATS * BEAT   # seconds
XFADE = 0.6                  # crossfade seconds for the seam

# Chord progression  i - VI - III - VII  (Am - F - C - G)
def n(name):
    # equal temperament, A4 = 440
    names = ["C","C#","D","D#","E","F","F#","G","G#","A","A#","B"]
    pitch = names.index(name[:-1]) if len(name) == 2 else names.index(name[:-1])
    octave = int(name[-1])
    semis = (octave - 4) * 12 + (pitch - 9)   # relative to A4
    return 440.0 * (2 ** (semis / 12.0))

CHORDS = [
    [n("A2"), n("C4"), n("E4"), n("A4")],   # Am
    [n("F2"), n("A3"), n("C4"), n("F4")],   # F
    [n("C3"), n("E4"), n("G4"), n("C5")],   # C
    [n("G2"), n("B3"), n("D4"), n("G4")],   # G
]

def saw_soft(phase):
    # band-limited-ish: a few harmonics of a sine -> warm pad
    return (math.sin(phase)
            + 0.35 * math.sin(2 * phase)
            + 0.15 * math.sin(3 * phase)) / 1.5

def tri(phase):
    return (2.0 / math.pi) * math.asin(math.sin(phase))

def render(total_s):
    nsamp = int(total_s * SR)
    buf = [0.0] * nsamp
    bar_len = CHORD_BEATS * BEAT
    # ---- Pad ----
    for i in range(nsamp):
        t = i / SR
        bar = int(t / bar_len) % BARS
        chord = CHORDS[bar]
        # slow tremolo to keep the pad alive
        trem = 0.85 + 0.15 * math.sin(2 * math.pi * 0.12 * t)
        s = 0.0
        for f in chord:
            s += saw_soft(2 * math.pi * f * t)
        s = (s / len(chord)) * 0.22 * trem
        buf[i] += s
    # ---- Arpeggio (eighth notes, triangle voice) ----
    eighth = BEAT / 2.0
    nnotes = int(total_s / eighth) + 1
    for k in range(nnotes):
        t0 = k * eighth
        bar = int(t0 / bar_len) % BARS
        chord = CHORDS[bar]
        f = chord[k % 4]
        if (k % 4) >= 2:
            f *= 2.0   # lift the upper steps an octave for sparkle
        dur = eighth * 0.95
        a, d = 0.006, dur
        for j in range(int(dur * SR)):
            idx = int(t0 * SR) + j
            if idx >= nsamp:
                break
            tt = j / SR
            env = min(tt / a, 1.0) * math.exp(-3.0 * tt / d)
            buf[idx] += tri(2 * math.pi * f * (t0 + tt)) * 0.10 * env
    # ---- gentle sub bass on the root, every bar ----
    for bar_i in range(int(total_s / bar_len) + 1):
        t0 = bar_i * bar_len
        root = CHORDS[bar_i % BARS][0] / 2.0   # one octave down
        dur = bar_len * 0.98
        for j in range(int(dur * SR)):
            idx = int(t0 * SR) + j
            if idx >= nsamp:
                break
            tt = j / SR
            env = min(tt / 0.02, 1.0) * (1.0 - tt / dur) * 0.9 + 0.1
            buf[idx] += math.sin(2 * math.pi * root * (t0 + tt)) * 0.16 * env
    return buf

def main():
    total = LOOP_LEN + XFADE
    buf = render(total)
    loop_n = int(LOOP_LEN * SR)
    xf = int(XFADE * SR)
    out = buf[:loop_n]
    # equal-power crossfade: blend tail (rendered past the loop point) into head
    for i in range(xf):
        a = math.cos(0.5 * math.pi * i / xf)   # head weight  1 -> 0
        b = math.sin(0.5 * math.pi * i / xf)   # tail weight  0 -> 1
        out[i] = out[i] * a + buf[loop_n + i] * b
    # normalize with a little headroom
    peak = max(1e-6, max(abs(x) for x in out))
    g = 0.85 / peak
    out_dir = os.path.join(os.path.dirname(__file__), "..", "assets", "sfx")
    path = os.path.abspath(os.path.join(out_dir, "music_loop.wav"))
    with wave.open(path, "w") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        frames = bytearray()
        for x in out:
            v = int(max(-1.0, min(1.0, x * g)) * 32767)
            frames += struct.pack("<h", v)
        w.writeframes(bytes(frames))
    print("wrote", path, "%.1f KB" % (os.path.getsize(path) / 1024.0),
          "loop=%.2fs" % LOOP_LEN)

if __name__ == "__main__":
    main()
