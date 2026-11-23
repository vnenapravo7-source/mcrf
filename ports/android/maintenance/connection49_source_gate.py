"""Current human scope supersedes old per-stage sourceReady receipts.

Read-only gate: expansion to all profile variants must not accidentally run an
old Edge-only producer or reuse previously closed host evidence for changed code.
"""
import json
from pathlib import Path

def require_complete_source(root:Path,*,expanded_common:bool=False)->None:
    path=root/'ports/android/coordination/p0-49-profile-variations-2026-10-20.json'
    if not path.is_file():
        raise RuntimeError('P049 expanded source registry missing; no build/test authorized')
    gate=json.loads(path.read_text('utf-8-sig'))
    if not expanded_common:
        raise RuntimeError('old narrow P049 producer retired for expanded scope; use the whole expanded common recipe')
    if not (gate.get('sourceReady') is True and gate.get('readyForBuild') is True and
            gate.get('allowCommonVerification') is True and gate.get('p2Included') is False and
            gate.get('remaining')==[]):
        raise RuntimeError('P049 all-variations source package is incomplete; no isolated Edge build/test')
