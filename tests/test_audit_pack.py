import json, pathlib, subprocess, sys, tempfile, unittest, zipfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
class AuditTest(unittest.TestCase):
    def test_inventory(self):
        with tempfile.TemporaryDirectory() as temp:
            root=pathlib.Path(temp); mods=root/'mods'; mods.mkdir(); (root/'shaderpacks').mkdir()
            with zipfile.ZipFile(mods/'test.jar','w') as z:
                z.writestr('META-INF/neoforge.mods.toml','[[mods]]\nmodId="gpu_tape"\nversion="1.0.3"\n[[dependencies.gpu_tape]]\nmodId="super_resolution"\ntype="incompatible"\nversionRange="[0,)"\n')
                z.writestr('test.mixins.json',json.dumps({'package':'test','client':['RenderTargetMixin']}))
                z.writestr('test/RenderTargetMixin.class',b'not executable')
            (mods/'bad.jar').write_bytes(b'broken'); (mods/'inactive.jar.disabled').touch(); (root/'shaderpacks'/'Photon.zip').touch()
            report=json.loads(subprocess.check_output([sys.executable,str(ROOT/'tools/audit_pack.py'),str(root)]))
            self.assertEqual(report['active_jar_count'],2)
            self.assertEqual(report['disabled_files'],['inactive.jar.disabled'])
            self.assertEqual(report['shaderpacks'],['Photon.zip'])
            bad,good=report['jars']; self.assertTrue(bad['errors']); self.assertEqual(good['mods'][0]['modId'],'gpu_tape')
            self.assertEqual(good['dependencies']['gpu_tape'][0]['type'],'incompatible')
            self.assertEqual(good['mixins'][0]['classes'],['RenderTargetMixin']); self.assertEqual(len(good['sha256']),64)
if __name__=='__main__': unittest.main()
