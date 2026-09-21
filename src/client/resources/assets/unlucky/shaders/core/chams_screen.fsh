#version 330
#extension GL_ARB_separate_shader_objects : require

// Screen-space chams fragment shader: identical to minecraft:core/entity, but samples
// Sampler0 by the per-fragment screen position (reconstructed from the interpolated
// clip position) instead of the model UVs. The model still rasterizes its own
// silhouette, so the image shows only on the entity — and because it's sampled in
// screen space, it stays put while the model moves through it.

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
#ifdef PER_FACE_LIGHTING
layout(location = 2) in vec4 vertexPerFaceColorBack;
layout(location = 3) in vec4 vertexPerFaceColorFront;
#else
layout(location = 2) in vec4 vertexColor;
#endif

#ifndef EMISSIVE
layout(location = 4) in vec4 lightMapColor;
#endif

#ifndef NO_OVERLAY
layout(location = 5) in vec4 overlayColor;
#endif

layout(location = 6) in vec2 texCoord0;
layout(location = 8) in vec3 unlucky_clipPos;

layout(location = 0) out vec4 fragColor;

void main() {
    // per-fragment NDC -> screen UV in [0,1]; perspective-correct because we divide the
    // interpolated (x, y, w) here rather than interpolating the already-divided value
    vec2 screenUV = (unlucky_clipPos.xy / unlucky_clipPos.z) * 0.5 + 0.5;
    vec4 color = texture(Sampler0, screenUV);
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif

    // Fullbright, flat galaxy: deliberately ignore the model's lightmap / overlay /
    // directional shading so the image reads identically in any light. ColorModulator
    // carries any global tint. (The lit varyings stay declared to keep the entity ABI.)
    fragColor = color * ColorModulator;
}
