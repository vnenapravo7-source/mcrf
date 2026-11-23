"""Four thematic GIFs and section images from existing REAL phone recordings."""
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
from PIL import Image, ImageDraw

sys.path.insert(0, 'D:/MCRF/Tools/media-python')
import imageio_ffmpeg

FF = imageio_ffmpeg.get_ffmpeg_exe()
STAGE = Path('D:/MCRF/Builds/Android/real-tour-9be97b40b8e64d27b2a3b5941e016f83')
OUT = Path('D:/MCRF/Projects/MCRF/docs/screenshots')
CUTS = {
    'android-connection': [('connection',10,16), ('connection',20,29), ('disconnect-real',1,10)],
    'android-appearance': [('sections',28,33), ('sections',40,45)],
    'android-functionality': [('bs-section',6,12), ('sections',10,13), ('sections',20,23), ('sections',3,6)],
    'android-sections': [('connection',10,12), ('bs-section',6,9), ('sections',10,13),
                         ('sections',16,19), ('sections',20,23), ('sections',28,32)],
}
SHOTS = {
    'android-home': ('connection',10),
    'android-connected': ('connection',25),
    'android-servers': ('sections',5),
    'android-bs': ('bs-section',9),
    'android-routes': ('sections',10),
    'android-settings': ('sections',17),
    'android-dns': ('sections',21),
    'android-appearance': ('sections',30),
    'android-light': ('sections',42),
}
EXTRA_SHOTS = {
    'android-vpn-proxy': 'p0-network42-phone-059a673f01f448d1ae0ac65c62e7ee89/acceptance/05-vpn-proxy-defaults.png',
    'android-rule-editor': 'item41-phone-726fda39673c400f9048c87d49a4eb71/ui/03-route-editor.png',
    'android-logs': 'p0-network42-phone-059a673f01f448d1ae0ac65c62e7ee89/acceptance/44-logs-live.png',
}

def run(args):
    return subprocess.run([FF,'-v','error',*args], check=True,
                          stdout=subprocess.PIPE,stderr=subprocess.PIPE).stdout

def frame(clip, second, width=360, height=880):
    return Image.open(io.BytesIO(run(['-ss',str(second),'-i',str(STAGE/(clip+'.mp4')),
             '-frames:v','1','-vf',f'scale={width}:{height}:flags=lanczos',
             '-f','image2pipe','-vcodec','png','pipe:1']))).convert('RGB')

def main():
    receipts = []
    for name,cuts in CUTS.items():
        images=[]
        for clip,start,end in cuts:
            data=run(['-ss',str(start),'-i',str(STAGE/(clip+'.mp4')),'-t',str(end-start),
                      '-vf','fps=12.5,scale=360:880:flags=lanczos','-f','rawvideo','-pix_fmt','rgb24','pipe:1'])
            stride=360*880*3
            assert data and len(data)%stride==0
            images.extend(Image.frombytes('RGB',(360,880),data[n:n+stride]).quantize(
                colors=128,method=Image.Quantize.MEDIANCUT) for n in range(0,len(data),stride))
        target=OUT/(name+'.gif');durations=[80]*len(images);durations[-1]=800
        images[0].save(target,save_all=True,append_images=images[1:],duration=durations,
                       loop=0,optimize=True,disposal=1)
        with Image.open(target) as gif:
            assert gif.is_animated and gif.size==(360,880)
            count=gif.n_frames
            total=sum(gif.seek(i) or gif.info.get('duration',0) for i in range(count))
            sheet=Image.new('RGB',(1080,920),(18,24,34));d=ImageDraw.Draw(sheet)
            for i,offset in enumerate([0,count//2,count-1]):
                gif.seek(offset);sheet.paste(gif.convert('RGB'),(i*360,30));d.text((i*360+8,8),f'{name} / frame {offset}',fill='white')
            sheet.save(STAGE/(name+'-review.jpg'),quality=92)
        receipts.append({'file':target.name,'frames':count,'ms':total,'bytes':target.stat().st_size,
                         'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'cuts':cuts})
        print(target.name, count, total, target.stat().st_size, flush=True)
    sheet=Image.new('RGB',(1440,3700),(18,24,34));d=ImageDraw.Draw(sheet)
    for i,(name,(clip,second)) in enumerate(SHOTS.items()):
        target=OUT/(name+'.png');picture=frame(clip,second,540,1320);picture.save(target,optimize=True)
        x=(i%4)*360;y=(i//4)*920
        sheet.paste(picture.resize((360,880)),(x,y+30));d.text((x+8,y+8),name,fill='white')
        receipts.append({'file':target.name,'source':[clip,second], 'bytes':target.stat().st_size,
                         'sha256':hashlib.sha256(target.read_bytes()).hexdigest()})
    sheet.save(STAGE/'gallery-shots-review.jpg',quality=92)
    # Reviewed genuine UI captures: no password fields, keys, QR or subscription URL.
    for name,relative in EXTRA_SHOTS.items():
        source=Path('D:/MCRF/Projects/MCRF/work/ports-test-2026-10-14')/relative
        target=OUT/(name+'.png')
        with Image.open(source) as picture:
            assert picture.size==(1080,2640)
            picture.resize((540,1320),Image.Resampling.LANCZOS).save(target,optimize=True)
        receipts.append({'file':target.name,'source':relative,'bytes':target.stat().st_size,
                         'sha256':hashlib.sha256(target.read_bytes()).hexdigest()})
    (STAGE/'gallery-receipt.json').write_text(json.dumps({'real':True,'demo':False,
          'newPhoneActions':False,'outputs':receipts},ensure_ascii=False,indent=2),encoding='utf-8')

if __name__=='__main__':
    main()
