#!/usr/bin/env bash
# Builds docs/assets/kookaburra-tutorial.mp4 (1920x1080, H.264 + AAC, English subtitles burned in) and its poster.
#
#   1. frames:  mvn -f kookaburra-face/pom.xml test -Dtest=TutorialRecorder -Djacoco.skip=true -Dsurefire.failIfNoSpecifiedTests=false
#   2. video:   docs/tools/make-tutorial.sh
#
# Frames and the storyboard (frame, seconds, subtitle) come from kookaburra-face/target/tutorial; the background
# music is synthesised by OfficeBgm.java (original, no third-party rights). Needs ffmpeg with libass and libx264.
#
# @Author: Fred Feng
# @Date: 15/10/2026
# @Version 1.0.0
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/kookaburra-face/target/tutorial"
WORK="$SRC/build"
OUT="$ROOT/docs/assets/kookaburra-tutorial.mp4"
POSTER="$ROOT/docs/assets/kookaburra-tutorial-poster.jpg"
XFADE=0.7
FONT="Helvetica Neue"
mkdir -p "$WORK"

# ---- storyboard → ffmpeg filter graph + ASS subtitles -----------------------------------------------------------
python3 - "$SRC" "$WORK" "$XFADE" "$FONT" <<'PY'
import sys, pathlib
src, work, x, font = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), float(sys.argv[3]), sys.argv[4]
scenes = [l.split('\t') for l in (src / 'scenes.tsv').read_text(encoding='utf-8').splitlines() if l.strip()]
inputs, chains, events = [], [], []
length = 0.0
starts = []
for i, (frame, secs, text) in enumerate(scenes):
    d = float(secs)
    inputs += ['-loop', '1', '-framerate', '30', '-t', f'{d + (x if i else 0):.3f}', '-i', str(src / 'frames' / frame)]
    chains.append(f'[{i}:v]format=yuv420p,setsar=1[v{i}]')
    starts.append(0.0 if i == 0 else length - x)
    length = d if i == 0 else length + d
last = 'v0'
acc = float(scenes[0][1])
for i in range(1, len(scenes)):
    offset = acc - x
    chains.append(f'[{last}][v{i}]xfade=transition=fade:duration={x}:offset={offset:.3f}[x{i}]')
    last = f'x{i}'
    acc = offset + x + float(scenes[i][1])
total = acc
def ts(t):
    h, m = int(t // 3600), int(t % 3600 // 60)
    return f'{h}:{m:02d}:{t % 60:05.2f}'
import re
for i, (frame, secs, text) in enumerate(scenes):
    text = re.sub(r'\[[^\]]*\]', lambda m: m.group().replace(' ', '\\h'), text)   # keep "[ n elements ]" on one line
    a = starts[i] + (x / 2 if i else 0.2)
    b = starts[i] + float(secs) + (x if i else 0) - x / 2
    events.append(f'Dialogue: 0,{ts(a)},{ts(min(b, total - 0.3))},Sub,,0,0,0,,{{\\fad(250,250)}}{text}')
ass = f"""[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080
WrapStyle: 0
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Sub,{font},50,&H00FFFFFF,&H00FFFFFF,&H00140E0B,&H64000000,0,0,0,0,100,100,0,0,1,2,1,2,140,140,62,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
""" + '\n'.join(events) + '\n'
(work / 'subs.ass').write_text(ass, encoding='utf-8')
(work / 'inputs.txt').write_text('\n'.join(inputs) + '\n', encoding='utf-8')
graph = ';'.join(chains) + f';[{last}]subtitles={work / "subs.ass"}:fontsdir=/System/Library/Fonts[vout]'
(work / 'graph.txt').write_text(graph, encoding='utf-8')
(work / 'length.txt').write_text(f'{total:.3f}', encoding='utf-8')
print(f'{len(scenes)} scenes, {total:.1f} s')
PY

LENGTH="$(cat "$WORK/length.txt")"

# ---- background music ----------------------------------------------------------------------------------------------
java "$ROOT/docs/tools/OfficeBgm.java" "$LENGTH" "$WORK/bgm.wav"

# ---- render ------------------------------------------------------------------------------------------------------------
INPUTS=()
while IFS= read -r line; do INPUTS+=("$line"); done < "$WORK/inputs.txt"
N=$(( ${#INPUTS[@]} / 8 ))
ffmpeg -y -loglevel error -stats "${INPUTS[@]}" -i "$WORK/bgm.wav" \
  -filter_complex "$(cat "$WORK/graph.txt");[$N:a]highpass=f=40,lowpass=f=9000,loudnorm=I=-20:TP=-2:LRA=7[aout]" \
  -map "[vout]" -map "[aout]" -t "$LENGTH" \
  -c:v libx264 -preset slow -crf 23 -tune stillimage -pix_fmt yuv420p -r 30 \
  -c:a aac -b:a 128k -ar 44100 -movflags +faststart "$OUT"

# ---- poster: the title frame with a play badge ----------------------------------------------------------------
FIRST="$(head -1 "$SRC/scenes.tsv" | cut -f1)"
SECS="${LENGTH%.*}"
CLOCK="$(printf '%d\\:%02d' $((SECS / 60)) $((SECS % 60)))"
ffmpeg -y -loglevel error -i "$SRC/frames/$FIRST" -vf "drawbox=x=(iw-760)/2:y=930:w=760:h=100:color=0x00E5FF@0.95:t=fill,\
drawtext=fontfile='/Library/Fonts/Arial Unicode.ttf':text='▶':fontcolor=0x0B0E14:fontsize=74:x=(w-760)/2+56:y=960,\
drawtext=fontfile=/System/Library/Fonts/HelveticaNeue.ttc:text='Watch the tutorial (${CLOCK})':fontcolor=0x0B0E14:fontsize=48:x=(w-760)/2+140:y=955,\
scale=1280:-2" -q:v 3 "$POSTER"

ls -lh "$OUT" "$POSTER"
ffprobe -v error -show_entries format=duration,size -of default=nw=1 "$OUT"
