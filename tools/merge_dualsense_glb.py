"""Merge DualSense OBJ parts into a single gamepad.glb for calibration UI."""
from pathlib import Path
import trimesh

obj_dir = Path(r"app/src/main/assets/peripheral3d/models/dualsense_obj")
out = Path(r"app/src/main/assets/peripheral3d/models/gamepad.glb")

files = sorted(obj_dir.glob("*.obj"))
print("objs", len(files))
meshes = []
for f in files:
    try:
        m = trimesh.load(str(f), force="mesh", process=False)
        if isinstance(m, trimesh.Scene):
            for g in m.geometry.values():
                if hasattr(g, "vertices") and len(g.vertices):
                    meshes.append(g)
        elif hasattr(m, "vertices") and len(m.vertices):
            meshes.append(m)
        print("ok", f.name)
    except Exception as e:
        print("fail", f.name, e)

if not meshes:
    raise SystemExit("no meshes")

combined = trimesh.util.concatenate(meshes)
bounds = combined.bounds
center = (bounds[0] + bounds[1]) / 2.0
combined.apply_translation(-center)
extents = bounds[1] - bounds[0]
scale = 2.6 / float(max(extents))
combined.apply_scale(scale)
# Dark body tint (vertex colors) so it looks like a controller without textures.
combined.visual.face_colors = [36, 44, 58, 255]
out.parent.mkdir(parents=True, exist_ok=True)
combined.export(str(out))
print("wrote", out.resolve(), "faces", len(combined.faces), "bytes", out.stat().st_size)
