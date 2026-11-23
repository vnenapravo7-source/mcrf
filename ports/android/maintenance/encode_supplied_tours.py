"""Encode reviewed human clips; omit credential editor and launcher transition."""
import hashlib
import argparse
import json
from pathlib import Path
import subprocess
import sys
from PIL import Image
sys.path.insert(0,'D:/MCRF/Tools/media-python')
import imageio_ffmpeg
FF=imageio_ffmpeg.get_ffmpeg_exe()
INPUT=Path('C:/Users/dieonfire/Downloads')
STAGE=Path('D:/MCRF/Builds/Android/human-videos-20261020')
PLAN={
    'android-connection':('1111',[(0,28.3)]),
    'android-appearance':('3333',[(0,7),(11,31.2)]),
    'android-functionality':('2222',[(0,16),(22,43)]),
    'android-sections':('2222',[(43,78.8)]),
}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--compact',action='store_true');args=parser.parse_args()
    output=STAGE/'compact' if args.compact else STAGE
    output.mkdir(exist_ok=True)
    outputs=[]
    for name,(source,cuts) in PLAN.items():
        parts=[];labels=[]
        if len(cuts)>1:
            parts.append('[0:v]split='+str(len(cuts))+''.join(f'[in{i}]' for i in range(len(cuts))))
        for i,(start,end) in enumerate(cuts):
            incoming=f'[in{i}]' if len(cuts)>1 else '[0:v]'
            parts.append(incoming+f'trim=start={start}:end={end},setpts=PTS-STARTPTS[v{i}]');labels.append(f'[v{i}]')
        incoming='[v0]'
        if len(cuts)>1:
            parts.append(''.join(labels)+f'concat=n={len(cuts)}:v=1:a=0[joined]');incoming='[joined]'
        fps=10 if args.compact and name in ['android-functionality','android-sections'] else 15
        parts.append(incoming+f'fps={fps},scale=360:-2:flags=lanczos,split[s0][s1]')
        parts.append('[s0]palettegen=max_colors=192:stats_mode=full[pal]')
        dither='none' if args.compact else 'bayer:bayer_scale=3'
        parts.append(f'[s1][pal]paletteuse=dither={dither}:diff_mode=rectangle[out]')
        dest=output/(name+'.gif')
        subprocess.run([FF,'-v','error','-n','-i',str(INPUT/(source+'.mp4')),
            '-filter_complex',';'.join(parts),'-map','[out]','-an','-loop','0',str(dest)],
            check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        with Image.open(dest) as gif:
            assert gif.is_animated and gif.size==(360,880)
            count=gif.n_frames;duration=sum(gif.seek(i) or gif.info.get('duration',0) for i in range(count))
            for i,at in enumerate([0,count//3,2*count//3,count-1]):
                gif.seek(at);gif.convert('RGB').save(output/f'{name}-check-{i}.png')
        row={'name':name,'source':source+'.mp4','cuts':cuts,'frames':count,'durationMs':duration,
             'bytes':dest.stat().st_size,'sha256':hashlib.sha256(dest.read_bytes()).hexdigest()}
        outputs.append(row);print(json.dumps(row),flush=True)
    (output/'encoding-receipt.json').write_text(json.dumps({'humanSupplied':True,'synthetic':False,
          'secretsEditorExcluded':True,'sourcePlaybackSpeed':1,'files':outputs},indent=2),encoding='utf-8')

if __name__=='__main__':main()
