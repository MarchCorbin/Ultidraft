# -*- mode: python ; coding: utf-8 -*-

from PyInstaller.utils.hooks import collect_all, collect_submodules

hiddenimports = collect_submodules("ultidraft")
datas = [("src/ultidraft/assets/ultidraft.ico", "ultidraft/assets")]
binaries = []

for package in ("edge_tts", "aiohttp", "certifi"):
    pkg_datas, pkg_binaries, pkg_hidden = collect_all(package)
    datas += pkg_datas
    binaries += pkg_binaries
    hiddenimports += pkg_hidden

hiddenimports += [
    "PySide6.QtMultimedia",
    "PySide6.QtTextToSpeech",
    "winrt",
    "winrt.runtime",
    "winrt.windows.foundation",
    "winrt.windows.globalization",
    "winrt.windows.media.speechrecognition",
]

a = Analysis(
    ["src/ultidraft/__main__.py"],
    pathex=["src"],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="Ultidraft",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    icon="ultidraft.ico",
)

coll = COLLECT(
    exe,
    a.binaries,
    a.zipfiles,
    a.datas,
    strip=False,
    upx=False,
    name="Ultidraft",
)
