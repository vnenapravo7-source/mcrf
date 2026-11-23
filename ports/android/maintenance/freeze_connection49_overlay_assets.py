"""Complete the omitted overlay assets without changing any tested input."""
import json,hashlib,shutil
from pathlib import Path
root=Path('D:/MCRF/Projects/MCRF')
stage=Path(json.loads((root/'ports/android/coordination/p0-49-compatibility-stage.json').read_text('utf-8'))['stage'])
assert not (stage/'overlay-assets.json').exists()
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
rows=[]
for name in ['fragment-write.txt','fragment_test.go.txt']:
    rel='ports/android/optional-cores/patches/'+name
    source=root/rel;dest=stage/'native'/rel
    assert source.is_file() and not source.is_symlink()
    dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,dest)
    assert sha(source)==sha(dest);rows.append({'path':rel,'sha256':sha(dest)})
(stage/'overlay-assets.json').write_text(json.dumps(rows,indent=2),encoding='utf-8')
print('PINNED_OVERLAY_ASSETS_FROZEN_2')
