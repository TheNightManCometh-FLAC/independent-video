import sys; sys.path.insert(0,'.')
from wordmark import *
from final import SPEC
m=mask(SPEC,3); r,c,co=layers(m,3)
out=sys.argv[1]
r.save(out+"/wm_red.png",optimize=True); c.save(out+"/wm_blue.png",optimize=True); co.save(out+"/wm_core.png",optimize=True)
p=preview(r,c,co,m.size[0]*0.005).convert("RGB"); p.save("wm_preview.png"); print(m.size)
