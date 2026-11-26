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
loc=gl('GetUniformLocation',i,u,C.c_char_p)(program,b'edgeStrength');assert loc>=0;gl('Uniform1f',None,i,f)(loc,.65)
def run(w,h,ow,oh,values,mutable=False):
    ids=(u*2)();gl('CreateTextures',None,u,i,C.POINTER(u))(0x0DE1,2,ids)
    fbos=(u*2)()
    if mutable:
        for tex,tw,th in [(ids[0],w,h),(ids[1],ow,oh)]:
            gl('BindTexture',None,u,u)(0x0DE1,tex)
            gl('TexParameteri',None,u,u,i)(0x0DE1,0x2801,0x2600)
            gl('TexParameteri',None,u,u,i)(0x0DE1,0x2800,0x2600)
            gl('TexImage2D',None,u,i,i,i,i,i,u,u,p)(0x0DE1,0,0x8058,tw,th,0,0x1908,0x1401,None)
        gl('BindTexture',None,u,u)(0x0DE1,0)
        gl('CreateFramebuffers',None,i,C.POINTER(u))(2,fbos)
        for framebuffer,tex in zip(fbos,ids):
            gl('NamedFramebufferTexture',None,u,u,u,i)(framebuffer,0x8CE0,tex,0)
            assert gl('CheckNamedFramebufferStatus',u,u,u)(framebuffer,0x8D40)==0x8CD5
    else:
        storage=gl('TextureStorage2D',None,u,i,u,i,i);storage(ids[0],1,0x8058,w,h);storage(ids[1],1,0x8058,ow,oh)
    data=(b*len(values))(*values);gl('TextureSubImage2D',None,u,i,i,i,i,i,u,u,p)(ids[0],0,0,0,w,h,0x1908,0x1401,data)
    bind_image=gl('BindImageTexture',None,u,u,i,b,i,u,u);bind_image(0,ids[0],0,0,0,0x88B8,0x8058);bind_image(1,ids[1],0,0,0,0x88B9,0x8058)
    gl('DispatchCompute',None,u,u,u)((ow+7)//8,(oh+7)//8,1);gl('MemoryBarrier',None,u)(0x20|0x100|0x8)
    out=(b*(ow*oh*4))();gl('GetTextureImage',None,u,i,u,u,i,p)(ids[1],0,0x1908,0x1401,len(out),out)
    assert gl('GetError',u)()==0
    if mutable: gl('DeleteFramebuffers',None,i,C.POINTER(u))(2,fbos)
    gl('DeleteTextures',None,i,C.POINTER(u))(2,ids);return list(out)
assert run(2,3,7,11,[37,128,241,255]*6)==[37,128,241,255]*77
pattern=[0,0,0,255,255,255,255,255]*2
assert run(2,2,2,2,pattern)==pattern
out=run(2,2,8,4,pattern)
assert out[:4]==[0,0,0,255] and out[28:32]==[255,255,255,255]
assert out[12]<96 and out[16]>159
assert run(1,1,9,3,[1,2,3,128])==[1,2,3,128]*27
assert run(2,3,7,11,[37,128,241,255]*6,True)==[37,128,241,255]*77
assert run(2,2,8,4,pattern,True)==run(2,2,8,4,pattern)
print('Direct mutable FBO texture tests PASS')
gl('UseProgram',None,u)(0)
gl('DeleteProgram',None,u)(program)
gl('DeleteShader',None,u)(shader)
current(display,None,None,None)
bind(E,'eglDestroyContext',u,p,p)(display,context)
bind(E,'eglTerminate',u,p)(display)
print('GPU shader tests PASS: constant, identity, edge, alpha, odd extents')
