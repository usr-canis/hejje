import json, math, uharfbuzz as hb
from fontTools.ttLib import TTFont
from fontTools.pens.basePen import BasePen
FONT='/home/user/hejje/app/src/main/res/font/mallige_bold.ttf'
blob=hb.Blob.from_file_path(FONT); face=hb.Face(blob); font=hb.Font(face)
buf=hb.Buffer(); buf.add_str("ಹೆಜ್ಜೆ"); buf.guess_segment_properties(); hb.shape(font,buf,{})
tt=TTFont(FONT); gs=tt.getGlyphSet(); order=tt.getGlyphOrder()
class Flat(BasePen):
    def __init__(s,gs,dx,dy): super().__init__(gs); s.c=[]; s.cur=None; s.dx=dx; s.dy=dy
    def P(s,p): return (p[0]+s.dx, -(p[1]+s.dy))
    def _moveTo(s,p): s.cur=[s.P(p)]; s.last=p
    def _lineTo(s,p): s.cur.append(s.P(p)); s.last=p
    def _curveToOne(s,a,b,c):
        p0=s.last
        for i in range(1,13):
            t=i/12; mt=1-t
            x=mt**3*p0[0]+3*mt*mt*t*a[0]+3*mt*t*t*b[0]+t**3*c[0]; y=mt**3*p0[1]+3*mt*mt*t*a[1]+3*mt*t*t*b[1]+t**3*c[1]
            s.cur.append(s.P((x,y)))
        s.last=c
    def _qCurveToOne(s,a,c):
        p0=s.last
        for i in range(1,9):
            t=i/8; mt=1-t
            s.cur.append(s.P((mt*mt*p0[0]+2*mt*t*a[0]+t*t*c[0], mt*mt*p0[1]+2*mt*t*a[1]+t*t*c[1])))
        s.last=c
    def _closePath(s):
        if s.cur: s.cur.append(s.cur[0]); s.c.append(s.cur); s.cur=None
    _endPath=_closePath
# Glyphs overlap (the vowel sign sits on the letter), so merge them into one clean outline:
# each glyph's contours even-odd into a shape, then the union of all glyphs.
from shapely.geometry import Polygon
from shapely.ops import unary_union
x=0; shapes=[]
for info,pos in zip(buf.glyph_infos, buf.glyph_positions):
    pen=Flat(gs, x+pos.x_offset, pos.y_offset); gs[order[info.codepoint]].draw(pen); x+=pos.x_advance
    g=None
    for c in pen.c:
        if len(c)<4: continue
        poly=Polygon(c).buffer(0)
        g=poly if g is None else g.symmetric_difference(poly)
    if g is not None: shapes.append(g)
union=unary_union(shapes)
polys=list(union.geoms) if hasattr(union,'geoms') else [union]
contours=[]
for poly in polys:
    contours.append(list(poly.exterior.coords))
    for ring in poly.interiors: contours.append(list(ring.coords))
xs=[p[0] for c in contours for p in c]; ys=[p[1] for c in contours for p in c]
minx,maxx,miny,maxy=min(xs),max(xs),min(ys),max(ys)
def L(c): return sum(math.dist(c[i],c[i+1]) for i in range(len(c)-1))
out=[{"pts":[[round(px-minx,1),round(py-miny,1)] for px,py in c],"len":round(L(c),1)} for c in contours]
out.sort(key=lambda c:-c["len"])
json.dump({"w":maxx-minx,"h":maxy-miny,"upem":tt["head"].unitsPerEm,"baseline":-miny,"contours":out},open('glyphs.json','w'))
print(len(contours),'contours; size',maxx-minx,maxy-miny,'lens',[c['len'] for c in out])
