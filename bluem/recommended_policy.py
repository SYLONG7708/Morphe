"""Strict Morphe stable release / nonexperimental recommended app selection."""
import re
from collections import Counter

def version(value):
    if not isinstance(value,str) or not re.fullmatch(r'\d+(?:\.\d+){1,3}',value):
        raise ValueError('Not a stable numeric version')
    return tuple(map(int,value.split('.')))

def recommended(release, metadata, sdk=28):
    if release.get('prerelease') is not False or release.get('draft') is not False:
        raise ValueError('Only published stable releases are allowed')
    tag=release['tag_name']
    patch_version=tag.removeprefix('v')
    version(patch_version)
    if metadata.get('version')!=patch_version:
        raise ValueError('Patch metadata must match the verified release tag')
    counts=Counter()
    for patch in metadata['patches']:
        if patch.get('default') is not True: continue
        for pkg in patch.get('compatiblePackages') or []:
            if pkg.get('packageName')!='com.google.android.youtube': continue
            eligible=set()
            for t in pkg.get('targets') or []:
                if t.get('isExperimental') is not False: continue
                v=t['version'];version(v)
                if not isinstance(t.get('minSdk'),int) or t['minSdk']>sdk: continue
                eligible.add(v)
            counts.update(eligible)
    if not counts: raise ValueError('No explicitly stable YouTube target')
    best=max(counts.values())
    result=max((v for v,n in counts.items() if n==best),key=version)
    return {'policy':'morphe-recommended-stable','patches_version':patch_version,
            'youtube_version':result,'prerelease':False,'experimental':False,
            'source':'MorpheApp/morphe-patches','default_patch_support_count':best}
