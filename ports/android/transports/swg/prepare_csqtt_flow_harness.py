"""Extract exact transformed orchestration into an OFFLINE fake-provider seam.

No parallel reimplementation of get_credentials/get/retry. Every copied block
is byte-bound to the generated real adapter. Only transport/profile dependencies
are test doubles; no HTTP client implementation is linked into this harness.
"""
import hashlib
import json
import re
from pathlib import Path

HERE=Path(__file__).resolve().parent


def masked(source):
    result=list(source);i=0
    while i<len(source):
        start=i;end=None
        if source.startswith("//",i):
            end=source.find("\n",i);end=len(source) if end<0 else end
        elif source.startswith("/*",i):
            depth=1;j=i+2
            while j<len(source) and depth:
                if source.startswith("/*",j):depth+=1;j+=2
                elif source.startswith("*/",j):depth-=1;j+=2
                else:j+=1
            if depth:raise ValueError("Unclosed Rust comment")
            end=j
        else:
            raw=re.match(r'r(#+)?"',source[i:])
            if raw:
                suffix='"'+(raw.group(1) or "");j=source.find(suffix,i+len(raw.group()))
                if j<0:raise ValueError("Unclosed Rust raw literal")
                end=j+len(suffix)
            elif source[i]=='"':
                j=i+1
                while j<len(source):
                    if source[j]=='\\':j+=2
                    elif source[j]=='"':end=j+1;break
                    else:j+=1
                if end is None:raise ValueError("Unclosed Rust literal")
            elif source[i]=="'":
                char=re.match(r"'(?:\\(?:u\{[0-9a-fA-F_]+\}|x[0-9a-fA-F]{2}|.)|[^'\\])'",source[i:])
                if char:end=i+len(char.group())
        if end is not None:
            result[start:end]=[" " if c!="\n" else "\n" for c in source[start:end]];i=end
        else:i+=1
    return "".join(result)


def block(source,anchor):
    if source.count(anchor)!=1:raise ValueError("Flow source anchor changed: "+anchor)
    start=source.index(anchor);code=masked(source);opening=code.index("{",start);depth=1;end=opening+1
    while end<len(code) and depth:
        depth+=(code[end]=="{")-(code[end]=="}");end+=1
    if depth:raise ValueError("Unclosed Rust block")
    return source[start:end]


def prepare(output):
    evidence={}
    for key in ("g251","g253"):
        crate=output / "adapters" / key / "rust-client"
        sources={name:(crate / name).read_text(encoding="utf-8") for name in ("auth.rs","worker.rs","vk_js_calls.rs")}
        selected={}
        def take(file,anchor,within=None):
            full=sources[file];value=block(block(full,within) if within else full,anchor)
            selected[file+":"+anchor]=hashlib.sha256(value.encode()).hexdigest()
            return value
        auth_methods="\n".join(take("auth.rs",anchor) for anchor in (
            "pub async fn get_credentials(","async fn mcrf_fetch_credentials(","async fn vk_calls_with_retries("))
        auth_types="\n".join(prefix+take("auth.rs",anchor) for prefix,anchor in (
            ("#[derive(Clone)]\n","pub struct TurnCredentials"),
            ("#[derive(Debug)]\n","pub struct CallUnavailable"),
            ("#[derive(Clone,Copy,Debug)]\n","pub enum VkRateLimitType"),
            ("#[derive(Debug)]\n","pub struct VkRateLimitError")))
        worker_types="\n".join(prefix+take("worker.rs",anchor) for prefix,anchor in (
            ("","struct HashAvailability"),("","impl HashAvailability"),("","pub struct GroupCredentials"),
            ("","struct CachedCredentials"),("#[derive(Clone)]\n","struct CredentialLease"),
            ("#[derive(Default)]\n","struct CredentialCache")))
        worker_methods="\n".join(take("worker.rs",anchor,"impl GroupCredentials {") for anchor in (
            "fn is_unavailable(","fn is_exhausted(","async fn get("))
        cache_methods="\n".join(take("worker.rs",anchor,"impl CredentialCache {") for anchor in (
            "fn reserve(","fn store(","fn next_quota_delay("))
        worker_helpers="\n".join(take("worker.rs",anchor) for anchor in (
            "pub fn credential_failure_delay(","fn quota_rotation_delay(","fn credential_stream_id(","fn same_turn_credentials("))
        bootstrap="\n".join(take("vk_js_calls.rs",anchor) for anchor in (
            "async fn retry<","fn mcrf_api_error(","fn check_api_error(","fn json_scalar_string("))
        # Bind the fake transport boundary to all three real transformed send
        # helpers. Fail preparation if even one guard is absent/moved after await.
        guards=[]
        for file,anchor in (("auth.rs","async fn post_empty("),("auth.rs","async fn post_form("),("vk_js_calls.rs","async fn post_form(")):
            method=take(file,anchor)
            guard=re.search(r"(?:crate::auth::)?mcrf_vk_backoff::provider_allowed\(\)\?;",method)
            if guard is None or ".await" in masked(method[:guard.start()]):
                raise ValueError("Missing pre-operation VK guard: "+file+":"+anchor)
            guards.append(guard.group())
        provider_guard=guards[0]
        destination=output / "admission-tests/flows" / key;destination.mkdir(parents=True)
        values={"AUTH_TYPES":auth_types,"AUTH_METHODS":auth_methods,"WORKER_TYPES":worker_types,
                "WORKER_METHODS":worker_methods,"CACHE_METHODS":cache_methods,"WORKER_HELPERS":worker_helpers,"BOOTSTRAP":bootstrap,
                "PROVIDER_GUARD":provider_guard}
        for name in ("auth.rs","worker.rs","bootstrap.rs","flow.rs"):
            template=(HERE / "vk_backoff_harness" / (name+".in")).read_text(encoding="utf-8")
            for marker,value in values.items():template=template.replace("// MCRF_"+marker,value)
            (destination / name).write_text(template,encoding="utf-8",newline="\n")
        (destination / "mcrf_vk_backoff.rs").write_bytes((crate / "mcrf_vk_backoff.rs").read_bytes())
        evidence[key]={"sourceSha256":{name:hashlib.sha256((crate/name).read_bytes()).hexdigest() for name in sources},"blocks":selected}
    (output / "admission-tests/extracted-flow-source.json").write_text(json.dumps(evidence,indent=2)+"\n",encoding="utf-8")
