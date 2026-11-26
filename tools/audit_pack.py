#!/usr/bin/env python3
"""Read-only NeoForge instance audit. Never imports or runs classes from mod JARs."""
import argparse, hashlib, json, pathlib, shutil, subprocess, tomllib, zipfile

LIMIT = 2 * 1024 * 1024
META = ('META-INF/neoforge.mods.toml', 'META-INF/mods.toml', 'fabric.mod.json')
IRIS_TYPES = ('net.irisshaders.iris.Iris', 'net.irisshaders.iris.api.v0.IrisApi',
    'net.irisshaders.iris.shaderpack.ShaderPack', 'net.irisshaders.iris.shaderpack.option.ShaderPackOptions',
    'net.irisshaders.iris.shaderpack.option.OptionSet', 'net.irisshaders.iris.shaderpack.option.StringOption',
    'net.irisshaders.iris.shaderpack.option.MergedStringOption', 'net.irisshaders.iris.shaderpack.option.values.OptionValues')
KEYWORDS = ('render', 'framebuffer', 'rendertarget', 'shader', 'opengl', 'glstate', 'pipeline', 'depth', 'texture', 'sodium', 'iris', 'vulkan', 'mixin')

def read(z, name):
    if z.getinfo(name).file_size > LIMIT:
        raise ValueError('metadata exceeds size limit')
    return z.read(name).decode('utf-8')

def sha256(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()

def jar(path):
    item = {'file':path.name,'sha256':sha256(path),'mods':[], 'dependencies':{}, 'mixins':[], 'render_candidates':[], 'errors':[]}
    try:
        with zipfile.ZipFile(path) as z:
            names=set(z.namelist())
            for n in META:
                if n not in names: continue
                try:
                    if n.endswith('.toml'):
                        data=tomllib.loads(read(z,n)); item['mods'] += data.get('mods',[]); item['dependencies'].update(data.get('dependencies',{}))
                    else:
                        data=json.loads(read(z,n)); item['mods'].append({'modId':data.get('id'),'version':data.get('version'),'loader':'fabric metadata'}); item['fabric_dependencies']={k:data.get(k,{}) for k in ('depends','breaks','conflicts')}
                except (ValueError, KeyError, TypeError) as e: item['errors'].append(f'{n}: {e}')
            # Metadata often contains descriptions; keep only identity fields.
            item['mods']=[{k:m.get(k) for k in ('modId','version','displayName','loader') if k in m} for m in item['mods'] if isinstance(m,dict)]
            for n in sorted(names):
                if n.endswith('.json') and 'mixin' in n.lower() and 'refmap' not in n.lower():
                    try:
                        d=json.loads(read(z,n))
                        if isinstance(d,dict) and any(k in d for k in ('mixins','client','server')):
                            classes=[str(c) for k in ('mixins','client','server') for c in d.get(k,[]) if isinstance(d.get(k),list)]
                            item['mixins'].append({'file':n,'package':d.get('package'),'plugin':d.get('plugin'),'classes':classes})
                    except (ValueError, KeyError, TypeError) as e: item['errors'].append(f'{n}: {e}')
            item['render_candidates']=[n for n in sorted(names) if n.endswith('.class') and any(k in n.lower() for k in KEYWORDS)][:1500]
            item['nested_jars']=[n for n in sorted(names) if n.endswith('.jar')]
            item['native_libraries']=[n for n in sorted(names) if n.endswith(('.so','.dll','.dylib'))]
            if 'net/irisshaders/iris/Iris.class' in names and shutil.which('javap'):
                item['iris_signatures']={}
                for cls in IRIS_TYPES:
                    if cls.replace('.','/')+'.class' not in names: continue
                    try:
                        p=subprocess.run(['javap','-public','-classpath',str(path.resolve()),cls],capture_output=True,text=True,timeout=15)
                        item['iris_signatures'][cls]={'returncode':p.returncode,'output':p.stdout[:30000],'error':p.stderr[:1000]}
                    except subprocess.TimeoutExpired: item['errors'].append('javap timeout: '+cls)
    except (OSError, zipfile.BadZipFile, RuntimeError) as e: item['errors'].append(str(e))
    return item

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('instance',type=pathlib.Path);a=p.parse_args()
    root=a.instance.expanduser(); mods=root/'mods'
    if not mods.is_dir(): p.error('instance must contain mods/; use Modrinth Open folder')
    entries=[]
    for f in sorted(mods.iterdir()):
        if f.is_file() and f.name.lower().endswith('.jar'):
            try: entries.append(jar(f))
            except (OSError, ValueError) as e: entries.append({'file':f.name,'errors':[str(e)]})
    result={'schema':1,'instance':root.name,'active_jar_count':len(entries),'jars':entries,'disabled_files':[f.name for f in sorted(mods.iterdir()) if f.is_file() and f.name.endswith('.disabled')], 'shaderpacks':[f.name for f in sorted((root/'shaderpacks').iterdir())] if (root/'shaderpacks').is_dir() else [],'notes':['Mixin class names are candidates, not verified targets. Nested JARs are listed, not analyzed. Metadata versions may be placeholders. No logs, configs or credentials collected.']}
    print(json.dumps(result,ensure_ascii=False,indent=2,default=str))
if __name__=='__main__': main()
