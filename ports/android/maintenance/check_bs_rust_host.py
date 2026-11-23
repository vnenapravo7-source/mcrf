"""Compile/test the unfinished boundary in an isolated host stage, never an APK."""
import argparse
import json
import os
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "transports/swg"))
from bs_engine_inputs import sha, require


def check(output, toolchain, ndk):
    output = pathlib.Path(output).absolute()
    require(not output.exists(), "Fresh host-check stage required")
    source = ROOT / "transports/swg/csqtt_multicall"
    output.mkdir(parents=True)
    files = {p.name:sha(p) for p in source.iterdir() if p.is_file()}
    receipt = {"state":"CHECKING", "scope":"Host selector/rejection only; no wire adapters or protocol acceptance", "sources":files}
    try:
        tools = output / "host-tools"
        tools.mkdir()
        shutil.copyfile(pathlib.Path(ndk) / "toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-dlltool.exe", tools / "dlltool.exe")
        rustc = pathlib.Path(toolchain) / "bin/rustc.exe"
        env = dict(os.environ)
        env["PATH"] = os.pathsep.join(map(str,[tools,pathlib.Path(toolchain)/"bin",pathlib.Path(toolchain)/"lib/rustlib/x86_64-pc-windows-gnu/bin/self-contained"])) + os.pathsep + env.get("PATH", "")
        receipt["rustcSha256"] = sha(rustc)
        commands = [
            [str(rustc), "--edition=2024", "--test", str(source/"lib.rs"), "-o", str(output/"boundary-tests.exe")],
            [str(output/"boundary-tests.exe")],
            [str(rustc), "--edition=2024", "--crate-type=rlib", "--crate-name=mcrf_csqtt_multicall_boundary", str(source/"lib.rs"), "-o", str(output/"boundary.rlib")],
            [str(rustc), "--edition=2024", str(source/"main.rs"), "--extern", "mcrf_csqtt_multicall_boundary="+str(output/"libboundary.rlib"), "-o", str(output/"boundary.exe")]]
        # rustc requires an rlib basename starting with lib.
        commands[2][-1] = str(output/"libboundary.rlib")
        for index, command in enumerate(commands):
            with (output/f"command-{index}.log").open("wb") as log:
                result = subprocess.run(command,cwd=output,env=env,stdout=log,stderr=subprocess.STDOUT)
            require(result.returncode==0, f"Host command {index} failed; log preserved")
        children = []
        for generation in ("2.0","2.1.5","2.1.9","2.5.1","2.5.2","2.5.3"):
            child_env = dict(env, MCRF_BS_RUST_MODE=generation)
            children.append((generation,subprocess.Popen([str(output/"boundary.exe"),"--mcrf-generation="+generation],env=child_env,cwd=output,stdout=subprocess.PIPE,stderr=subprocess.PIPE)))
        receipt["parallelProcesses"] = []
        for generation, child in children:
            stdout, stderr = child.communicate(timeout=30)
            require(child.returncode==78 and not stdout and b"ports are incomplete" in stderr, "Boundary must refuse before protocol startup")
            receipt["parallelProcesses"].append({"generation":generation,"exitCode":child.returncode,"rejectedBeforeNetwork":True})
        bad = subprocess.run([str(output/"boundary.exe"),"--mcrf-generation=2.0"],env=dict(env,MCRF_BS_RUST_MODE="2.5.1"),capture_output=True,timeout=30)
        require(bad.returncode==78 and b"conflicting" in bad.stderr, "Cross-generation mismatch accepted")
        receipt["state"] = "HOST_BOUNDARY_PASS_PORTING_REQUIRED"
        return receipt
    except Exception as error:
        receipt.update(state="FAILED_PRESERVED",error=str(error))
        raise
    finally:
        require(files=={p.name:sha(p) for p in source.iterdir() if p.is_file()}, "Boundary source changed during check")
        (output/"receipt.json").write_text(json.dumps(receipt,indent=2)+"\n",encoding="utf-8")


if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output",required=True)
    parser.add_argument("--toolchain",default="D:/MCRF/Builds/Tools/rust-1.97.1-gnu/toolchain")
    parser.add_argument("--ndk",default="C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018")
    args=parser.parse_args()
    print(json.dumps(check(args.output,args.toolchain,args.ndk)))
