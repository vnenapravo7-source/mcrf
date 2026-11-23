"""Inspect the complete supplied clips before choosing public GIF excerpts."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import sys
from PIL import Image, ImageDraw
sys.path.insert(0,'D:/MCRF/Tools/media-python')
import imageio_ffmpeg
FF=imageio_ffmpeg.get_ffmpeg_exe()
STAGE=Path('D:/MCRF/Builds/Android/human-videos-20261020')
INPUT=Path('C:/Users/dieonfire/Downloads')

def run(args):
    return subprocess.run([FF,'-v','error',*args],stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True).stdout

def frame(name,t,width=240):
    data=run(['-ss',str(t),'-i',str(INPUT/(name+'.mp4')),'-frames:v','1','-vf',f'scale={width}:-2',
          '-f','image2pipe','-vcodec','png','pipe:1'])
    return Image.open(io.BytesIO(data)).convert('RGB')

def main():
    STAGE.mkdir(exist_ok=True)
    meta=[]
    for name in ['1111','2222','3333']:
        path=INPUT/(name+'.mp4')
        info=imageio_ffmpeg.read_frames(str(path));m=next(info);info.close()
        duration=m['duration'];times=list(range(0,int(duration),2))
        if duration>1:times.append(round(duration-0.6,2))
        pages=[]
        for page in range(0,len(times),15):
            subset=times[page:page+15];pictures=[frame(name,t) for t in subset]
            height=pictures[0].height;sheet=Image.new('RGB',(5*240,3*(height+28)),(17,24,33));d=ImageDraw.Draw(sheet)
            for i,(t,picture) in enumerate(zip(subset,pictures)):
                x=i%5*240;y=i//5*(height+28);sheet.paste(picture,(x,y+28));d.text((x+5,y+7),f'{name} / {t}s',fill='white')
            out=STAGE/f'{name}-review-{page//15}.jpg';sheet.save(out,quality=90);pages.append(str(out))
        meta.append({'name':name,'metadata':m,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'contactSheets':pages})
    (STAGE/'review.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(meta,ensure_ascii=False))

if __name__=='__main__':main()
