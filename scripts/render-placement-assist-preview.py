#!/usr/bin/env python3
"""Render the focused production-geometry export with the shared vanilla renderer."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

repo = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--client-jar", type=Path, required=True)
parser.add_argument("--ops-root", type=Path, default=repo.parent / "ruscrafting-ops")
args = parser.parse_args()
output = repo / "build/reports/placement-assist-v3"
scene = output / "scene.json"
if not scene.is_file():
    parser.error("First run ./gradlew test --tests ru.arc.buildertools.BuilderPlacementAssistPreviewTest")
expected = re.search(r"^minecraft-client-sha1: ([a-f0-9]{40})$", (repo / "visual-preview.yml").read_text(), re.M).group(1)
client_sha1 = hashlib.sha1(args.client_jar.read_bytes()).hexdigest()
if client_sha1 != expected:
    parser.error("Client JAR must match the Minecraft version and SHA-1 pinned in visual-preview.yml")

renderer = args.ops_root / "scripts/location-atelier"
with (output / "palette.json").open("w") as palette:
    subprocess.run([sys.executable, str(renderer / "vanilla_assets.py"), str(args.client_jar), str(scene)], stdout=palette, check=True)
template = (renderer / "workshop-preview.template.html").read_text()
template = template.replace("Мебельная мастерская", "Помощник строительства")
template = template.replace("Состояние мастерской", "Состояние рамки")
template = template.replace("Техническое превью: станки, этапы и готовая мебель; пол условный, без мира и NPC",
    "Офлайн: одна клетка под прицелом — ряд, пол и стена из геометрии ArcBuilder; нативный glow не воспроизводится")
template = template.replace('data-view="front" aria-pressed="true"', 'data-view="front" aria-pressed="false"')
template = template.replace('data-view="player" aria-pressed="false"', 'data-view="player" aria-pressed="true"')
template = template.replace('<div id="viewport"></div>', '<div id="viewport"></div><div class="aim-marker" aria-hidden="true"></div>')
template = template.replace('</style>', '''
    .aim-marker{display:none;position:absolute;left:50%;top:50%;z-index:2;width:15px;height:15px;transform:translate(-50%,-50%);pointer-events:none}
    .aim-marker::before,.aim-marker::after{content:"";position:absolute;background:#fff;box-shadow:0 0 1px 1px #232c2d}
    .aim-marker::before{left:7px;top:0;width:1px;height:15px}.aim-marker::after{top:7px;left:0;height:1px;width:15px}
    body:has([data-view="player"][aria-pressed="true"]) .aim-marker{display:block}
  </style>''')
(output / "template.html").write_text(template)
subprocess.run(["node", str(renderer / "build-workshop-preview.mjs"), "--scene", str(scene),
    "--palette", str(output / "palette.json"), "--template", str(output / "template.html"),
    "--output", str(output / "index.html")], check=True)
sources = ["src/main/kotlin/ru/arc/buildertools/BuilderPlacementAssistGeometry.kt",
    "src/main/kotlin/ru/arc/buildertools/BuilderDisplayGeometry.kt",
    "src/main/kotlin/ru/arc/buildertools/BuilderPlacementAssist.kt",
    "src/main/kotlin/ru/arc/buildertools/BuilderBlockDisplayRenderer.kt",
    "src/test/kotlin/ru/arc/buildertools/BuilderPlacementAssistPreviewTest.kt",
    "scripts/render-placement-assist-preview.py"]
(output / "provenance.json").write_text(json.dumps({
    "kind": "offline source-derived geometry and pinned vanilla models; not native-client acceptance",
    "clientSha1": client_sha1,
    "sourceSha256": {path: hashlib.sha256((repo / path).read_bytes()).hexdigest() for path in sources},
    "sceneSha256": hashlib.sha256(scene.read_bytes()).hexdigest(),
    "runtimeGaps": ["Minecraft glow postprocessing", "transparent material blending", "input and packet timing", "world lighting and occlusion"],
}, ensure_ascii=False, indent=2) + "\n")
