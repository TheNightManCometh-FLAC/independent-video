import sys; sys.path.insert(0,'.')
from wordmark import *
SPEC=dict(font_top="Archivo",w_top=450,size_top=30,track_top=15,x_top={"wdth":125},font_bot="Archivo",w_bot=800,size_bot=104,x_bot={"wdth":125},gap=12,pad=58)
if __name__=="__main__":
    m=mask(SPEC,2); r,c,co=layers(m,2)
    rows=[preview(r,c,co,o).convert("RGB") for o in (3,5,12)]
    W=max(x.width for x in rows); sheet=Image.new("RGB",(W,sum(x.height for x in rows)),(10,10,12)); y=0
    for x in rows: sheet.paste(x,(0,y)); y+=x.height
    sheet.save("final_sheet.png"); print(m.size)
