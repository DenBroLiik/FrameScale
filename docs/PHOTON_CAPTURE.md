# Photon temporal input investigation

Verified source references:
- Iris: https://github.com/IrisShaders/Iris/blob/12ea3bf16b39c0a70ec31dc9de1483c77bd3ccc3/common/src/main/java/net/irisshaders/iris/pipeline/CompositeRenderer.java
- Photon: https://github.com/sixthsurge/photon/blob/2a3d502c17d2e310412c717be47241f014cba7fc/shaders/program/c4_taa_exposure.fsh
- Formats: https://github.com/sixthsurge/photon/blob/2a3d502c17d2e310412c717be47241f014cba7fc/shaders/include/buffers.glsl

The upstream world0/composite4 wrapper includes c4_taa_exposure. It consumes colortex0, history colortex5, depthtex0, previous camera matrices, camera position and raw taa_offset. It writes [0,5]; later passes consume the temporal result/exposure. The HDR source is R11F_G11F_B10F; TAAU uses a reduced region within full-sized textures, so copying the entire allocation as reduced color would be wrong. The alpha channel of history stores temporal age/exposure metadata; replacing it blindly would break downstream shaders.

The new observer arms for five seconds, requires active Photon TAA/TAAU options and OpenGL 4.5, checks actual active program and pass output signature, then inspects texture level dimensions/formats and active uniforms with exact types. Iris ping-pong state identifies the expected source texture, but sampler remaps/custom textures and the exact shader source are not verified. Uniforms may be optimized out or renamed; missing values are reported. The observer does not retain texture handles, copy pixels, modify GL bindings or interrupt the draw. No source fingerprint yet; a matching pack name is insufficient proof of semantics. Commands: /framescale capture start, report, uniforms, stop. Only run in a world. CI checks Java contract and compiles mod; actual Mixin/Photon execution requires in-game test. GPUTape and DH compatibility unknown.

Next: verify the exact installed Photon pack, active render rectangle and actual sampler bindings; implement GPU-only RGB packed to RGBA16F conversion, depth copy and static camera reprojection with valid jitter and coordinates, then handle moving objects, LOD, hand and transparency separately. Camera-only vectors are not sufficient for production DLSS. Preserve Photon exposure/postprocess; do not disable TAAU until output contract is verified.

## Temporal input compute (alpha.2)

Caller supplies verified crop, nonaliasing textures, unjittered current-to-previous clip transform and current jitter in NDC. Motion is previous minus current in render pixels, GL lower-left origin (+Y up). Invalid projection/background gives zero motion and validity=0. Validity is geometric only, not an occlusion or reactive mask. No dynamic-object motion, hand, transparency or LOD handling. Photon raw taa_offset is not NGX pixel jitter. Runtime sampler and crop verification still required. No in-game dispatch. CI checks packed HDR crop, depth, identity, translation, jitter, invalid projection and bounds on Mesa.

## In-game diagnostic alpha.3

`/framescale capture gpu WIDTH HEIGHT` arms one compute dispatch on the candidate pass. Supply a known valid lower-left crop; allocation bounds do not prove the active render region. Sampler IDs must match expected Iris textures; compare mode must be disabled. Private temporary textures (estimated maximum 128 MiB) are discarded after dispatch; no retained buffers or pixel readback. Identity transform/zero jitter intentionally avoid claiming unverified camera semantics. `/framescale capture gpu` polls the last reported GPU interval (tick-driven). Timing excludes shader compilation/allocation, includes post-dispatch barrier. No image output verification in game, no performance improvement claimed. Existing TAAU draw continues. Runtime testing required.
