# AMLL Core Background Renderers

QPlayer's Pixi and Mesh Gradient lyric backgrounds contain Java/Skija and SkSL
ports of background renderers from @applemusic-like-lyrics/core. The lyric-page
cover playback animation is adapted from @applemusic-like-lyrics/react-full.

- Upstream project: https://github.com/amll-dev/applemusic-like-lyrics
- Upstream packages: packages/core, packages/react-full
- Upstream license: GNU Affero General Public License v3.0 only
- Upstream copyright: Copyright (c) 2022-2024 AMLL Contributors

Ported source areas:

- src/bg-render/pixi-renderer.ts (including its historical S-curve deformation)
- src/bg-render/mesh-renderer/index.ts
- src/bg-render/mesh-renderer/cp-presets.ts
- src/bg-render/mesh-renderer/cp-generate.ts
- src/bg-render/mesh-renderer/mesh.vert.glsl
- src/bg-render/mesh-renderer/mesh.frag.glsl
- packages/react-full/src/components/Cover/index.module.css

QPlayer modifications made in 2026:

- The PixiJS renderer and its filters were rewritten as a SkSL runtime effect.
- The TypeScript/WebGL Mesh Gradient renderer was rewritten as Java 21 with
  Skija drawVertices.
- GLSL was rewritten as a SkSL runtime-effect resource.
- Control-point presets were moved into a data resource.
- Album transitions and static-frame caching were integrated with QPlayer's
  shared desktop/Android lyric compositor.
- The cover's pause/resume scale and directional CSS cubic-bezier transitions
  were rewritten as a reusable QML component with an explicit timing-curve
  evaluator for qml4j.

The complete AGPL-3.0-only license is included beside this notice as LICENSE.
