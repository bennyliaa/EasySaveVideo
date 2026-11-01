#!/usr/bin/env python3
"""Create an original synthetic ESV media fixture using installed Pillow/FFmpeg.
No network requests, input media, packages, private files, or device access.
Usage: python3 generate_fixture.py [output_directory]
"""
import hashlib
import json
import math
from pathlib import Path
import struct
import subprocess
import sys
from PIL import Image, ImageDraw, ImageFont

OUT = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parent)
OUT.mkdir(parents=True, exist_ok=True)
VIDEO = OUT / 'esv_synthetic_8s_h264_aac.mp4'
WIDTH, HEIGHT, FPS, FRAMES = 640, 360, 30, 240
FONT = '/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf'
font = ImageFont.truetype(FONT, 23)
small = ImageFont.truetype(FONT, 15)
cmd = [
    'ffmpeg', '-hide_banner', '-y', '-f', 'rawvideo', '-pixel_format', 'rgb24',
    '-video_size', f'{WIDTH}x{HEIGHT}', '-framerate', str(FPS), '-i', 'pipe:0',
    '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000:duration=8',
    '-map', '0:v:0', '-map', '1:a:0', '-t', '8', '-c:v', 'libx264',
    '-profile:v', 'baseline', '-level:v', '3.0', '-pix_fmt', 'yuv420p',
    '-preset', 'medium', '-crf', '26', '-g', '30', '-keyint_min', '30',
    '-sc_threshold', '0', '-threads', '1', '-c:a', 'aac', '-b:a', '64k',
    '-ac', '1', '-ar', '48000', '-af', 'volume=0.3', '-movflags', '+faststart',
    '-map_metadata', '-1', '-metadata', 'title=ESV Original Synthetic QA Fixture',
    '-metadata', 'comment=Generated geometric animation and 440 Hz tone; no source media.',
    str(VIDEO),
]
with (OUT / 'ffmpeg_encode.log').open('wb') as log:
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=log)
    try:
        for n in range(FRAMES):
            t = n / FPS
            img = Image.new('RGB', (WIDTH, HEIGHT), '#14243b')
            draw = ImageDraw.Draw(img)
            # Three stable color landmarks help identify channel/orientation errors.
            for x, color, name in [(20, '#ef5350', 'RED'), (225, '#66bb6a', 'GREEN'), (430, '#42a5f5', 'BLUE')]:
                draw.rounded_rectangle((x, 20, x + 190, 89), radius=12, fill=color)
                draw.text((x + 13, 43), name, fill='#081020', font=font)
            # Continuous independent motion makes frozen/incorrectly sought frames visible.
            phase = 2 * math.pi * t / 4
            cx = int(320 + 227 * math.sin(phase))
            cy = int(163 + 26 * math.cos(phase))
            draw.ellipse((cx - 24, cy - 24, cx + 24, cy + 24), fill='#ffd54f', outline='white', width=3)
            sx = int(35 + (n / (FRAMES - 1)) * 520)
            draw.rectangle((sx, 211, sx + 44, 255), fill='#ba68c8', outline='white', width=3)
            draw.line((20, 278, 620, 278), fill='#52637a', width=4)
            progress = int(20 + 600 * n / (FRAMES - 1))
            draw.line((20, 278, progress, 278), fill='#80deea', width=4)
            draw.rectangle((0, 293, 639, 359), fill='#08111f')
            draw.text((16, 299), f'ESV TEST  t={t:05.2f}s  frame={n:03d}/239', fill='white', font=font)
            draw.text((16, 334), '640x360 | 30 fps | Original synthetic media | 440 Hz', fill='#a5c9ea', font=small)
            proc.stdin.write(img.tobytes())
    finally:
        proc.stdin.close()
    if proc.wait() != 0:
        raise RuntimeError('FFmpeg encoding failed; inspect ffmpeg_encode.log')

probe = json.loads(subprocess.check_output([
    'ffprobe', '-v', 'error', '-count_frames', '-show_streams', '-show_format', '-of', 'json', str(VIDEO)
]))
(OUT / 'ffprobe.json').write_text(json.dumps(probe, indent=2) + '\n')
v = next(s for s in probe['streams'] if s['codec_type'] == 'video')
a = next(s for s in probe['streams'] if s['codec_type'] == 'audio')
assert v['codec_name'] == 'h264' and v['pix_fmt'] == 'yuv420p'
assert (v['width'], v['height'], v['avg_frame_rate'], int(v['nb_read_frames'])) == (640, 360, '30/1', 240)
assert float(v['duration']) == 8.0 and float(probe['format']['duration']) == 8.0
assert a['codec_name'] == 'aac' and a['profile'] == 'LC' and a['sample_rate'] == '48000' and a['channels'] == 1
assert float(a['duration']) == 8.0
# A full audio/video decode with warnings treated as recorded QA output.
result = subprocess.run(['ffmpeg', '-hide_banner', '-v', 'error', '-xerror', '-i', str(VIDEO), '-map', '0', '-f', 'null', '-'], capture_output=True, text=True)
(OUT / 'ffmpeg_decode.log').write_text(result.stderr)
assert result.returncode == 0 and not result.stderr.strip(), result.stderr
# Verify progressive-playback layout by walking the MP4's top-level atoms.
raw = VIDEO.read_bytes()
atoms, offset = [], 0
while offset + 8 <= len(raw):
    size, kind = struct.unpack('>I4s', raw[offset:offset + 8])
    header = 8
    if size == 1:
        size = struct.unpack('>Q', raw[offset + 8:offset + 16])[0]
        header = 16
    elif size == 0:
        size = len(raw) - offset
    assert size >= header and offset + size <= len(raw)
    atoms.append({'type': kind.decode('ascii'), 'offset': offset, 'size': size})
    offset += size
assert offset == len(raw)
assert next(x['offset'] for x in atoms if x['type'] == 'moov') < next(x['offset'] for x in atoms if x['type'] == 'mdat')
# Four actual decoded frames, at t = 0/2/4/6 seconds, for visual inspection.
subprocess.run(['ffmpeg', '-hide_banner', '-v', 'error', '-y', '-i', str(VIDEO), '-vf', r'select=eq(n\,0)+eq(n\,60)+eq(n\,120)+eq(n\,180),scale=640:360,tile=2x2', '-frames:v', '1', '-update', '1', str(OUT / 'decoded_contact_sheet.png')], check=True)
manifest = {
    'filename': VIDEO.name,
    'purpose': 'EasySaveVideo UI/media QA fixture (thumbnail, playback, seek, duration, audio, save/export)',
    'provenance': 'Original programmatically drawn geometry/text and generated sine tone; no downloaded or private source media.',
    'duration_seconds': 8.0,
    'video': {'codec': 'H.264/AVC', 'profile': v['profile'], 'level': '3.0', 'pixel_format': 'yuv420p', 'width': 640, 'height': 360, 'frame_rate': '30/1', 'decoded_frames': 240},
    'audio': {'codec': 'AAC', 'profile': a['profile'], 'sample_rate_hz': 48000, 'channels': 1, 'signal': '440 Hz sine tone at low amplitude'},
    'size_bytes': len(raw),
    'sha256': hashlib.sha256(raw).hexdigest(),
    'faststart': True,
    'mp4_atoms': atoms,
    'verification': {'metadata_assertions': 'PASS', 'full_av_decode': 'PASS, zero decode errors', 'visual_contact_sheet': 'decoded_contact_sheet.png (0/2/4/6 s)', 'device_playback': 'NOT RUN; fixture prepared only'},
    'generation_dependencies': {'ffmpeg': subprocess.check_output(['ffmpeg', '-version'], text=True).splitlines()[0], 'python': sys.version.split()[0], 'pillow': __import__('PIL').__version__, 'font': FONT},
    'files': {'generator': 'generate_fixture.py', 'probe': 'ffprobe.json', 'encode_log': 'ffmpeg_encode.log', 'decode_log': 'ffmpeg_decode.log'},
}
(OUT / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
(OUT / 'SHA256SUMS').write_text(f"{manifest['sha256']}  {VIDEO.name}\n")
print(json.dumps(manifest, indent=2))
