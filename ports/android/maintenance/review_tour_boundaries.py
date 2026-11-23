"""Visual evidence at connection transitions and both sides of the privacy cut."""
from PIL import Image,ImageDraw
from review_supplied_tours import frame,STAGE

PLANS={
 '1111-timing':('1111',[0,0.5,1,1.5,11.5,12,12.5,13,13.5,14]),
 '3333-timing':('3333',[11.5,12,12.5,13,28.5,29,29.5,30,30.5,31]),
 '2222-privacy':('2222',[14.5,15,15.5,15.9,22,22.1,22.5,23]),
}
for name,(source,times) in PLANS.items():
    pictures=[frame(source,t,240) for t in times];height=pictures[0].height
    rows=(len(times)+4)//5;sheet=Image.new('RGB',(1200,rows*(height+24)),'#181818');draw=ImageDraw.Draw(sheet)
    for i,(t,pic) in enumerate(zip(times,pictures)):
        x=i%5*240;y=i//5*(height+24);draw.text((x+5,y+5),f'{source} {t}s',fill='white');sheet.paste(pic,(x,y+24))
    sheet.save(STAGE/(name+'.jpg'),quality=92)
    print(name)
