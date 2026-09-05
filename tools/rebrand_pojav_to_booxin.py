#!/usr/bin/env python3
"""Rebrand Pojav ABI strings in LWJGL jars + merge BooxinBridgeLoader/RendererInit."""
from __future__ import annotations

import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASM = ROOT / "tools" / "lwjgl-sdl" / "asm"
ASM_JAR = ASM / "asm-9.7.1.jar"
ASM_COMMONS = ASM / "asm-commons-9.7.jar"
PATCH_JAVA = ASM / "PatchPojavBrand.java"
BRIDGE_SRC = ROOT / "tools" / "bridge-patch" / "src"
JARS = [
    ROOT / "app/src/main/assets/app_runtime/lwjgl/lwjgl.jar",
    ROOT / "app/src/main/assets/app_runtime/lwjgl/lwjgl-java8.jar",
    ROOT / "app/src/main/assets/app_runtime/lwjgl/lwjgl-bridge-patch.jar",
]

STRING_MAP = [
    ("libpojavexec.so", "libbooxin_bridge.so"),
    ("BooxinPojavLoader", "BooxinBridgeLoader"),
    ("POJAV_RENDERER", "BOOXIN_RENDERER"),
    ("fixPojavGLContext", "fixBooxinGLContext"),
    ("pojavGetRequiredInstanceExtensions", "booxinGetRequiredInstanceExtensions"),
    ("pojavGetPhysicalDevicePresentationSupport", "booxinGetPhysicalDevicePresentationSupport"),
    ("pojavGetInstanceProcAddress", "booxinGetInstanceProcAddress"),
    ("pojavCreateWindowSurface", "booxinCreateWindowSurface"),
    ("pojavInitVulkanLoader", "booxinInitVulkanLoader"),
    ("pojavVulkanSupported", "booxinVulkanSupported"),
    ("pojavGetCurrentContext", "booxinGetCurrentContext"),
    ("pojavCreateContext", "booxinCreateContext"),
    ("pojavSetInjectorCallback", "booxinSetInjectorCallback"),
    ("pojavSetHitResultType", "booxinSetHitResultType"),
    ("pojavSetWindowHint", "booxinSetWindowHint"),
    ("pojavSwapInterval", "booxinSwapInterval"),
    ("pojavStartPumping", "booxinStartPumping"),
    ("pojavStopPumping", "booxinStopPumping"),
    ("pojavSwapBuffers", "booxinSwapBuffers"),
    ("pojavMakeCurrent", "booxinMakeCurrent"),
    ("pojavPumpEvents", "booxinPumpEvents"),
    ("pojavTerminate", "booxinTerminate"),
    ("pojavInitOpenGL", "booxinInitOpenGL"),
    ("pojavInit", "booxinInit"),
    ("pojavexec", "booxin_bridge"),
    (
        "mPojavRendererInit: Failed to find Pojav renderer name! Renderer-specific initialization may not work properly",
        "BooxinRendererInit: Failed to find Booxin renderer name! Renderer-specific initialization may not work properly",
    ),
    (
        "PojavRendererInit: Failed to find Pojav renderer name! Renderer-specific initialization may not work properly",
        "BooxinRendererInit: Failed to find Booxin renderer name! Renderer-specific initialization may not work properly",
    ),
]


def javac_cmd() -> list[str]:
    for p in [
        r"C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\javac.exe",
        r"C:\Program Files\Microsoft\jdk-21.0.8.9-hotspot\bin\javac.exe",
        "javac",
    ]:
        if p == "javac" or Path(p).is_file():
            return [p]
    raise SystemExit("javac not found")


def compile_patch_tool() -> Path:
    out_dir = ASM / "build_rebrand"
    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True)
    subprocess.check_call(
        javac_cmd()
        + ["-cp", f"{ASM_JAR};{ASM_COMMONS}", "-d", str(out_dir), str(PATCH_JAVA)]
    )
    return out_dir


def patch_jar(in_jar: Path, out_jar: Path, cp_dir: Path) -> None:
    subprocess.check_call(
        [
            "java",
            "-cp",
            f"{cp_dir};{ASM_JAR};{ASM_COMMONS}",
            "PatchPojavBrand",
            str(in_jar),
            str(out_jar),
        ]
    )


def rebuild_bridge_classes() -> Path:
    build = ROOT / "tools/bridge-patch/build_rebrand"
    if build.exists():
        shutil.rmtree(build)
    build.mkdir(parents=True)
    stubs = build / "stubs/org/lwjgl/system"
    stubs.mkdir(parents=True)
    (stubs / "FunctionProvider.java").write_text(
        "package org.lwjgl.system; public interface FunctionProvider {}\n", encoding="utf-8"
    )
    (stubs / "SharedLibrary.java").write_text(
        "package org.lwjgl.system; public interface SharedLibrary extends FunctionProvider { String getName(); }\n",
        encoding="utf-8",
    )
    classes = build / "classes"
    subprocess.check_call(
        javac_cmd()
        + [
            "-d",
            str(classes),
            str(stubs / "FunctionProvider.java"),
            str(stubs / "SharedLibrary.java"),
        ]
    )
    sources = [
        BRIDGE_SRC / "org/lwjgl/glfw/BooxinBridgeLoader.java",
        BRIDGE_SRC / "org/lwjgl/opengl/RendererInit.java",
    ]
    subprocess.check_call(
        javac_cmd()
        + ["-cp", str(classes), "-d", str(classes), *[str(s) for s in sources if s.is_file()]]
    )
    return classes


def merge_classes_into_jar(jar: Path, classes_dir: Path) -> None:
    tmp = jar.with_suffix(".jar.tmp")
    skip = {
        "org/lwjgl/glfw/BooxinPojavLoader.class",
        "org/lwjgl/glfw/BooxinBridgeLoader.class",
        "org/lwjgl/opengl/RendererInit.class",
    }
    with zipfile.ZipFile(jar, "r") as zin, zipfile.ZipFile(
        tmp, "w", compression=zipfile.ZIP_DEFLATED
    ) as zout:
        for item in zin.infolist():
            if item.filename in skip:
                continue
            zout.writestr(item, zin.read(item.filename))
        for path in classes_dir.rglob("*.class"):
            rel = path.relative_to(classes_dir).as_posix()
            if rel.startswith("org/lwjgl/system/"):
                continue
            zout.writestr(rel, path.read_bytes())
    tmp.replace(jar)


def scan_remaining(jar: Path) -> list[str]:
    hits = []
    with zipfile.ZipFile(jar) as z:
        for n in z.namelist():
            if not n.endswith(".class"):
                continue
            d = z.read(n)
            if b"pojav" in d.lower():
                found = set(re.findall(rb"[\x20-\x7e]{4,}", d))
                for s in sorted(found):
                    if b"pojav" in s.lower():
                        hits.append(f"{n}: {s.decode('ascii', 'ignore')}")
    return hits


def main() -> int:
    cp_dir = compile_patch_tool()
    classes = rebuild_bridge_classes()
    for jar in JARS:
        if not jar.is_file():
            print("skip missing", jar)
            continue
        backup = jar.with_suffix(jar.suffix + ".bak-pre-booxin-rebrand")
        if not backup.is_file():
            shutil.copy2(jar, backup)
            print("backed up", backup.name)
        tmp_out = jar.with_suffix(".rebrand.tmp.jar")
        patch_jar(jar, tmp_out, cp_dir)
        tmp_out.replace(jar)
        merge_classes_into_jar(jar, classes)
        left = scan_remaining(jar)
        print(f"== {jar.name} remaining pojav strings: {len(left)}")
        for h in left[:40]:
            print(" ", h)
    return 0


if __name__ == "__main__":
    sys.exit(main())
