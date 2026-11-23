"""Source-bound built-in Gooby overlay for fresh g251/g253 adapters only.
No Main/module capability or activation. Never edits pinned source inputs.
"""
from pathlib import Path
import shutil,hashlib
HERE=Path(__file__).resolve().parent

def once(text,old,new):
    if text.count(old)!=1:raise ValueError("Gooby source anchor changed")
    return text.replace(old,new,1)

def transform(main,stream,cargo):
    main=once(main,"mod turn_stream;","mod turn_stream;\nmod mcrf_gooby;")
    args='    #[arg(long, default_value = "udp")]\n    turn_transport: String,'
    main=once(main,args,args+'\n    #[arg(long, default_value_t = false)]\n    gooby: bool,\n    #[arg(long, default_value = "current")]\n    gooby_strategy: String,')
    main=once(main,'async fn run(arguments: Arguments) -> Result<i32> {','async fn run(arguments: Arguments) -> Result<i32> {\n    mcrf_gooby::configure(arguments.gooby,&arguments.gooby_strategy,&arguments.turn_transport)?;')
    main=once(main,'        "turn-transport",','        "turn-transport",\n        "gooby",\n        "gooby-strategy",')
    start=stream.index('pub async fn connect(');body=stream.index('    let socket = timeout(STREAM_CONNECT_TIMEOUT, TcpStream::connect(server))',start)
    header=stream[start:body]
    if header.count('    server: SocketAddr,')!=1 or not header.endswith(' {\n'):raise ValueError("Gooby connect boundary changed")
    inner=header.replace('pub async fn connect(','async fn connect_inner(',1).replace('    server: SocketAddr,','    server: SocketAddr,\n    gooby:Option<mcrf_gooby_builtin::Preset>,\n    roots:Option<Arc<ClientConfig>>,',1)
    stream=stream[:start]+header+'    connect_inner(endpoint,server,crate::mcrf_gooby::selected(),None).await\n}\n\n'+inner+stream[body:]
    stream=once(stream,'    let stream: BoxedTurnStream = match endpoint.transport {','    let socket=crate::mcrf_gooby::wrap(socket,gooby);\n    let stream: BoxedTurnStream = match endpoint.transport {')
    stream=once(stream,'let connector = TlsConnector::from(tls_config());','let connector = TlsConnector::from(roots.unwrap_or_else(tls_config));')
    stream+='\n#[cfg(test)]\n#[path="mcrf_gooby_stream_tests.rs"]\nmod mcrf_gooby_tests;\n'
    cargo=once(cargo,'[dependencies]\n','[dependencies]\nmcrf-gooby-builtin = { path = "../../../gooby_builtin" }\n')
    return main,stream,cargo

def inventory(root):return {str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in root.rglob('*') if p.is_file()}

def source_inventory():
    """Bind the overlay and every vendored implementation/license input."""
    names=['prepare_csqtt_gooby.py','mcrf_gooby.rs','mcrf_gooby_stream_tests.rs']
    names += ['gooby_builtin/'+name.replace('\\','/') for name in inventory(HERE/'gooby_builtin')]
    return {name:hashlib.sha256((HERE/name).read_bytes()).hexdigest() for name in sorted(names)}
def apply(crate):
    crate=Path(crate);paths=[crate/n for n in ['main.rs','turn_stream.rs','Cargo.toml']]
    changed=transform(*(p.read_text(encoding='utf-8') for p in paths))
    shared=crate.parents[2]/'gooby_builtin';source=HERE/'gooby_builtin'
    if shared.exists():
        if inventory(shared)!=inventory(source):raise ValueError("Gooby shared source changed")
    else:shutil.copytree(source,shared)
    for path,text in zip(paths,changed):path.write_text(text,encoding='utf-8',newline='\n')
    for name in ['mcrf_gooby.rs','mcrf_gooby_stream_tests.rs']:shutil.copyfile(HERE/name,crate/name)
