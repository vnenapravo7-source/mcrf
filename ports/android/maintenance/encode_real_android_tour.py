"""Encode actual human-authorized screenrecord clips. No invented UI or connection states."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
from PIL import Image, ImageDraw

sys.path.insert(0,'D:/MCRF/Tools/media-python')
import imageio_ffmpeg
FFMPEG=imageio_ffmpeg.get_ffmpeg_exe()
STAGE=Path('D:/MCRF/Builds/Android/real-tour-9be97b40b8e64d27b2a3b5941e016f83')
OUTPUT=Path('D:/MCRF/Projects/MCRF/docs/screenshots/android-tour.gif')

def frame(clip,second):
    data=subprocess.run([FFMPEG,'-v','error','-ss',str(second),'-i',str(STAGE/(clip+'.mp4')),
                         '-frames:v','1','-vf','scale=360:880','-f','image2pipe','-vcodec','png','pipe:1'],
                         check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE).stdout
    return Image.open(io.BytesIO(data)).convert('RGB')

p=argparse.ArgumentParser();p.add_argument('--review',action='store_true');args=p.parse_args()
if args.review:
    selections=[('connection',[10,13,16,20,25,30]),('disconnect-real',[1,3,4,5,7,10]),
                ('sections',[5,10,17,21,30,36]),('sections',[38,42,45])]
    if (STAGE/'bs-section.mp4').exists():selections.append(('bs-section',[3,6,9,13]))
    for number,(clip,times) in enumerate(selections):
        sheet=Image.new('RGB',(1080,1840),(22,26,34));draw=ImageDraw.Draw(sheet)
        for index,second in enumerate(times):
            x=(index%3)*360;y=(index//3)*920
            sheet.paste(frame(clip,second),(x,y+30));draw.text((x+8,y+8),f'{clip} / {second}s',fill='white')
        path=STAGE/f'review-{number}.jpg';sheet.save(path,quality=92)
        print(path)
else:
    # Cut only waiting/repeated footage. Preserve natural timing inside every excerpt.
    selections=[('connection',10,16),('connection',20,29),('disconnect-real',1,10),('bs-section',5,11),
                ('sections',5,13),('sections',15,18),('sections',20,23),('sections',28,33),('sections',40,45)]
    frames=[];durations=[]
    for clip,start,end in selections:
        data=subprocess.run([FFMPEG,'-v','error','-ss',str(start),'-i',str(STAGE/(clip+'.mp4')),'-t',str(end-start),
              '-vf','fps=12.5,scale=360:880:flags=lanczos','-f','rawvideo','-pix_fmt','rgb24','pipe:1'],
              check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE).stdout
        stride=360*880*3;assert len(data)%stride==0
        for offset in range(0,len(data),stride):
            frames.append(Image.frombytes('RGB',(360,880),data[offset:offset+stride]).quantize(colors=128,method=Image.Quantize.MEDIANCUT))
            durations.append(80)
    assert len(frames)>400
    durations[-1]=1000
    frames[0].save(OUTPUT,save_all=True,append_images=frames[1:],duration=durations,loop=0,optimize=True,disposal=1)
    with Image.open(OUTPUT) as check:
        assert check.is_animated and check.size==(360,880)
        count=check.n_frames;total=sum(check.seek(i) or check.info.get('duration',0) for i in range(count))
    receipt={'realPhone':True,'demo':False,'connectionSimulated':False,'personalServersHumanApproved':True,
             'sourceClips':selections,'encodedFrames':count,'durationMs':total,'size':[360,880],
             'bytes':OUTPUT.stat().st_size,'sha256':hashlib.sha256(OUTPUT.read_bytes()).hexdigest(),
             'content':'Actual Samsung UI; own saved servers; real connect/disconnect; VPN/BS, routes, DNS, appearance and light/dark switching; no auth editors'}
    OUTPUT.with_suffix('.receipt.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(receipt))
