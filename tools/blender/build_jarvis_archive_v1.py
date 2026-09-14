import bpy
import math
import os


ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
OUT = os.path.join(ROOT, 'frontend', 'public', 'assets', 'analysis-os', 'jarvis-archive-v1.glb')


def clear_scene():
    bpy.ops.object.select_all(action='SELECT')
    bpy.ops.object.delete(use_global=False)


def mat(name, color, metallic=0.0, roughness=0.5, transmission=0.0, alpha=1.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    bsdf = m.node_tree.nodes.get('Principled BSDF')
    bsdf.inputs['Base Color'].default_value = (*color, 1.0)
    if 'Metallic' in bsdf.inputs:
        bsdf.inputs['Metallic'].default_value = metallic
    if 'Roughness' in bsdf.inputs:
        bsdf.inputs['Roughness'].default_value = roughness
    if 'Transmission Weight' in bsdf.inputs:
        bsdf.inputs['Transmission Weight'].default_value = transmission
    elif 'Transmission' in bsdf.inputs:
        bsdf.inputs['Transmission'].default_value = transmission
    if 'IOR' in bsdf.inputs:
        bsdf.inputs['IOR'].default_value = 1.46
    if 'Alpha' in bsdf.inputs:
        bsdf.inputs['Alpha'].default_value = alpha
    if alpha < 1.0 or transmission > 0.0:
        m.surface_render_method = 'DITHERED'
    return m


def cube(name, size, loc, material, bevel=0.04):
    bpy.ops.mesh.primitive_cube_add(location=loc)
    o = bpy.context.object
    o.name = name
    o.scale = (size[0] / 2, size[1] / 2, size[2] / 2)
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    if bevel > 0:
        mod = o.modifiers.new('Bevel', 'BEVEL')
        mod.width = bevel
        mod.segments = 3
    o.data.materials.append(material)
    return o


def cylinder(name, radius, depth, loc, material, rot=(math.pi / 2, 0, 0), verts=24):
    bpy.ops.mesh.primitive_cylinder_add(vertices=verts, radius=radius, depth=depth, location=loc, rotation=rot)
    o = bpy.context.object
    o.name = name
    o.data.materials.append(material)
    return o


def torus(name, major, minor, loc, material):
    bpy.ops.mesh.primitive_torus_add(
        major_radius=major,
        minor_radius=minor,
        major_segments=48,
        minor_segments=10,
        location=loc,
        rotation=(math.pi / 2, 0, 0),
    )
    o = bpy.context.object
    o.name = name
    o.data.materials.append(material)
    return o


def main():
    clear_scene()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)

    body_m = mat('BodyWarmIvory', (0.68, 0.64, 0.58), metallic=0.08, roughness=0.34)
    inset_m = mat('InsetPaper', (0.88, 0.85, 0.79), metallic=0.02, roughness=0.48)
    frame_m = mat('FrameGraphite', (0.36, 0.36, 0.33), metallic=0.26, roughness=0.32)
    rail_m = mat('RailSteel', (0.56, 0.54, 0.50), metallic=0.32, roughness=0.30)
    accent_m = mat('ArchiveAmber', (0.43, 0.31, 0.17), metallic=0.34, roughness=0.24)
    spine_m = mat('ArchiveAmberSpine', (0.72, 0.53, 0.27), metallic=0.08, roughness=0.34, transmission=0.08, alpha=0.58)
    glass_m = mat('GlassCover', (0.94, 0.92, 0.88), metallic=0.0, roughness=0.22, transmission=0.70, alpha=0.68)
    inner_m = mat('InnerGraphite', (0.24, 0.25, 0.23), metallic=0.16, roughness=0.32)

    root = bpy.data.objects.new('JARVIS_ARCHIVE_ROOT', None)
    bpy.context.collection.objects.link(root)

    parts = []
    parts.append(cube('Body', (4.98, 0.31, 3.70), (0, 0, 0), body_m, 0.065))
    parts.append(cube('Inset', (4.72, 0.06, 3.44), (0, -0.185, 0), inset_m, 0.055))

    # Outer frame and seal rails.
    for x in (-2.31, 2.31):
        parts.append(cube('FrameSide', (0.08, 0.09, 3.34), (x, -0.235, 0), frame_m, 0.018))
        parts.append(cube('SealSide', (0.03, 0.028, 3.10), (x * 0.97, -0.29, 0), rail_m, 0.008))
    for z in (-1.65, 1.65):
        parts.append(cube('FrameTopBottom', (4.56, 0.09, 0.08), (0, -0.235, z), frame_m, 0.018))
        parts.append(cube('SealTopBottom', (4.42, 0.028, 0.03), (0, -0.29, z * 0.96), rail_m, 0.008))

    # Front glass and hardware.
    parts.append(cube('Glass', (4.64, 0.035, 3.34), (0, -0.325, -0.02), glass_m, 0.05))
    parts.append(cube('AmberSpine', (0.095, 0.04, 3.12), (-2.18, -0.39, -0.02), spine_m, 0.018))
    parts.append(cube('Latch', (0.78, 0.11, 0.17), (0, -0.37, 1.48), accent_m, 0.035))
    parts.append(cube('LabelCarrier', (4.28, 0.05, 0.70), (0, -0.37, 1.18), inset_m, 0.028))

    # Interior rails and rings.
    for x in (-1.06, 1.06):
        parts.append(cube('InnerRail', (0.06, 0.05, 2.40), (x, -0.345, -0.15), rail_m, 0.014))
    parts.append(cube('CenterBridge', (0.05, 0.055, 1.95), (0, -0.365, -0.05), inner_m, 0.012))
    parts.append(torus('RingTop', 0.48, 0.034, (0, -0.37, 0.36), inner_m))
    parts.append(torus('RingBottom', 0.48, 0.034, (0, -0.37, -0.48), inner_m))

    # Fasteners and micro hardware.
    for x in (-2.12, 2.12):
        for z in (-1.50, 1.50):
            parts.append(cylinder('Fastener', 0.055, 0.055, (x, -0.33, z), frame_m, verts=18))
    for x in (-1.70, 1.70):
        parts.append(cube('SideTab', (0.10, 0.06, 0.36), (x, -0.38, -1.28), accent_m, 0.018))

    # Small lower vent-like details to break up flatness.
    for i in range(-7, 8):
        parts.append(cube('LowerDetail', (0.17, 0.03, 0.022), (i * 0.28, -0.39, -1.49), rail_m, 0.006))

    for p in parts:
        p.parent = root

    bpy.context.view_layer.objects.active = root
    root.select_set(True)

    bpy.ops.export_scene.gltf(
        filepath=OUT,
        export_format='GLB',
        export_apply=True,
        export_materials='EXPORT',
        export_yup=True,
        export_cameras=False,
        export_lights=False,
    )
    print(f'WROTE {OUT}')


if __name__ == '__main__':
    main()

