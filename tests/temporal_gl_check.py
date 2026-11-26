"""Compile and execute the exact shipped GLSL in a disposable surfaceless EGL context."""
import ctypes as C, pathlib, os, sys
os.environ.setdefault('EGL_PLATFORM','surfaceless')
os.environ.setdefault('LIBGL_ALWAYS_SOFTWARE','1')
E=C.CDLL('libEGL.so.1'); G=C.CDLL('libGL.so.1')
i=C.c_int; u=C.c_uint; p=C.c_void_p; b=C.c_ubyte; f=C.c_float

def bind(lib,name,ret,*args):
    fn=getattr(lib,name); fn.restype=ret;fn.argtypes=args;return fn
get=bind(E,'eglGetDisplay',p,p); init=bind(E,'eglInitialize',u,p,C.POINTER(i),C.POINTER(i))
api=bind(E,'eglBindAPI',u,u); choose=bind(E,'eglChooseConfig',u,p,C.POINTER(i),C.POINTER(p),i,C.POINTER(i))
ctx=bind(E,'eglCreateContext',p,p,p,p,C.POINTER(i)); current=bind(E,'eglMakeCurrent',u,p,p,p,p)
display=get(None); major=i();minor=i();assert init(display,C.byref(major),C.byref(minor));assert api(0x30A2)
config=p(); n=i(); attrs=(i*5)(0x3033,1,0x3040,8,0x3038);assert choose(display,attrs,C.byref(config),1,C.byref(n)) and n.value
attrs=(i*7)(0x3098,4,0x30FB,5,0x30FD,1,0x3038);context=ctx(display,config,None,attrs);assert context and current(display,None,None,context)
def gl(name,ret,*args): return bind(G,'gl'+name,ret,*args)
print('Renderer:',gl('GetString',C.c_char_p,u)(0x1F01).decode())
shader=gl('CreateShader',u,u)(0x91B9); code=pathlib.Path(sys.argv[1]).read_bytes(); src=C.c_char_p(code)
gl('ShaderSource',None,u,i,C.POINTER(C.c_char_p),C.POINTER(i))(shader,1,C.byref(src),None);gl('CompileShader',None,u)(shader)
def status(kind,obj,flag):
    ok=i();gl('Get'+kind+'iv',None,u,u,C.POINTER(i))(obj,flag,C.byref(ok))
    if not ok.value:
        log=C.create_string_buffer(8192);gl('Get'+kind+'InfoLog',None,u,i,C.POINTER(i),p)(obj,len(log),None,log);raise RuntimeError(log.value.decode())
status('Shader',shader,0x8B81);program=gl('CreateProgram',u)();gl('AttachShader',None,u,u)(program,shader);gl('LinkProgram',None,u)(program);status('Program',program,0x8B82);gl('UseProgram',None,u)(program)

def loc(name):
    n=gl('GetUniformLocation',i,u,C.c_char_p)(program,name.encode());assert n>=0;return n
def run(matrix,jitter=(0.,0.),origin=(1,1)):
    w,h=11,7;rw,rh=7,5
    ids=(u*6)();gl('CreateTextures',None,u,i,C.POINTER(u))(0x0DE1,6,ids)
    formats=[0x8C3A,0x8CAC,0x881A,0x822E,0x822F,0x8232]
    for n,fmt in enumerate(formats):
        gl('TextureStorage2D',None,u,i,u,i,i)(ids[n],1,fmt,w if n<2 else rw,h if n<2 else rh)
        for param in (0x2801,0x2800):gl('TextureParameteri',None,u,u,i)(ids[n],param,0x2600)
    colors=[v for y in range(h) for x in range(w) for v in (float(x+1),float(y+1),.5)]
    depths=[.5]*(w*h);depths[2*w+3]=1.
    upload=gl('TextureSubImage2D',None,u,i,i,i,i,i,u,u,p)
    upload(ids[0],0,0,0,w,h,0x1907,0x1406,(f*len(colors))(*colors))
    upload(ids[1],0,0,0,w,h,0x1902,0x1406,(f*len(depths))(*depths))
    for n in range(2):gl('BindTextureUnit',None,u,u)(n,ids[n])
    for n in range(4):gl('BindImageTexture',None,u,u,i,b,i,u,u)(n,ids[n+2],0,0,0,0x88B9,formats[n+2])
    gl('Uniform2i',None,i,i,i)(loc('sourceOrigin'),*origin)
    gl('Uniform2i',None,i,i,i)(loc('renderExtent'),rw,rh)
    gl('Uniform2f',None,i,f,f)(loc('currentJitterNdc'),*jitter)
    gl('UniformMatrix4fv',None,i,i,b,C.POINTER(f))(loc('currentToPreviousClip'),1,0,(f*16)(*matrix))
    gl('DispatchCompute',None,u,u,u)((rw+7)//8,(rh+7)//8,1)
    gl('MemoryBarrier',None,u)(0x20|0x100|0x8)
    gl('PixelStorei',None,u,i)(0x0D05,1)
    result=[]
    for n,ch,fmt,ty,ctype in [(2,4,0x1908,0x1406,f),(3,1,0x1903,0x1406,f),(4,2,0x8227,0x1406,f),(5,1,0x8D94,0x1401,b)]:
        out=(ctype*(rw*rh*ch))()
        gl('GetTextureImage',None,u,i,u,u,i,p)(ids[n],0,fmt,ty,C.sizeof(out),out)
        result.append(list(out))
    assert gl('GetError',u)()==0
    gl('DeleteTextures',None,i,C.POINTER(u))(6,ids)
    return result
identity=[1.,0.,0.,0.,0.,1.,0.,0.,0.,0.,1.,0.,0.,0.,0.,1.]
c,d,m,v=run(identity)
assert c[:4]==[2.,2.,.5,1.] and c[-4:]==[8.,6.,.5,1.]
assert d[9]==1. and v[9]==0 and sum(v)==34
assert not any(m)
translated=identity.copy();translated[12]=2/7;translated[13]=2/5
c,d,m,v=run(translated)
assert v[0]==1 and abs(m[0]-1)<.002 and abs(m[1]-1)<.002
assert v[6]==0 and v[28]==0 and m[12:14]==[0.,0.]
c,d,m,v=run(identity,(.1,-.1))
assert not any(m) and v[0]==1
bad=identity.copy();bad[15]=-1.
c,d,m,v=run(bad)
assert not any(v) and not any(m) and c[:4]==[2.,2.,.5,1.]
c,d,m,v=run(identity,origin=(9,6))
assert v[0]==1 and v[1]==1 and v[2]==0 and c[8:12]==[0.,0.,0.,1.] and d[2]==1.
gl('UseProgram',None,u)(0)
gl('DeleteProgram',None,u)(program);gl('DeleteShader',None,u)(shader)
current(display,None,None,None)
bind(E,'eglDestroyContext',u,p,p)(display,context);bind(E,'eglTerminate',u,p)(display)
print('Temporal inputs GPU tests PASS: HDR crop, depth, motion, jitter, invalid projection, bounds')
