#version 330

// End-portal chams fragment shader: the vanilla rendertype_end_portal layer effect
// (verbatim COLORS / layer transforms, animated by GameTime) painted over the entity
// model in screen space — the starfield stays put while the model moves through it,
// exactly like standing inside an end portal. Differences from vanilla's shader:
//  - runs on the ENTITY vertex format via chams_screen.vsh, sampling by per-fragment
//    screen position (unlucky_clipPos) instead of the POSITION-format texProj0 —
//    vanilla's END_PORTAL pipeline can't draw entity models (see ARCHITECTURE.md §6);
//  - one sampler: every layer reads Sampler0 = the end_portal texture; the end-sky
//    background layer is a constant — the measured average of end_sky.png, which is
//    NOT dark (0.45, 0.34, 0.61): its COLORS[0] product is the portal's ambient
//    blue glow. The real sky layer is soft noise, so under the fifteen moving
//    speckle layers the constant is indistinguishable.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:matrix.glsl>
#moj_import <minecraft:globals.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
#ifdef PER_FACE_LIGHTING
in vec4 vertexPerFaceColorBack;
in vec4 vertexPerFaceColorFront;
#else
in vec4 vertexColor;
#endif

#ifndef EMISSIVE
in vec4 lightMapColor;
#endif

#ifndef NO_OVERLAY
in vec4 overlayColor;
#endif

in vec2 texCoord0;
in vec3 unlucky_clipPos;

out vec4 fragColor;

const vec3[] COLORS = vec3[](
    vec3(0.022087, 0.098399, 0.110818),
    vec3(0.011892, 0.095924, 0.089485),
    vec3(0.027636, 0.101689, 0.100326),
    vec3(0.046564, 0.109883, 0.114838),
    vec3(0.064901, 0.117696, 0.097189),
    vec3(0.063761, 0.086895, 0.123646),
    vec3(0.084817, 0.111994, 0.166380),
    vec3(0.097489, 0.154120, 0.091064),
    vec3(0.106152, 0.131144, 0.195191),
    vec3(0.097721, 0.110188, 0.187229),
    vec3(0.133516, 0.138278, 0.148582),
    vec3(0.070006, 0.243332, 0.235792),
    vec3(0.196766, 0.142899, 0.214696),
    vec3(0.047281, 0.315338, 0.321970),
    vec3(0.204675, 0.390010, 0.302066),
    vec3(0.080955, 0.314821, 0.661491)
);

const mat4 SCALE_TRANSLATE = mat4(
    0.5, 0.0, 0.0, 0.25,
    0.0, 0.5, 0.0, 0.25,
    0.0, 0.0, 1.0, 0.0,
    0.0, 0.0, 0.0, 1.0
);

mat4 end_portal_layer(float layer) {
    mat4 translate = mat4(
        1.0, 0.0, 0.0, 17.0 / layer,
        0.0, 1.0, 0.0, (2.0 + layer / 1.5) * (GameTime * 1.5),
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
    );

    mat2 rotate = mat2_rotate_z(radians((layer * layer * 4321.0 + layer * 9.0) * 2.0));

    mat2 scale = mat2((4.5 - layer / 4.0) * 2.0);

    return mat4(scale * rotate) * translate * SCALE_TRANSLATE;
}

void main() {
    // per-fragment NDC -> screen UV in [0,1]; perspective-correct because we divide the
    // interpolated (x, y, w) here rather than interpolating the already-divided value
    vec2 screenUV = (unlucky_clipPos.xy / unlucky_clipPos.z) * 0.5 + 0.5;
    // w = 1: the layer matrices only touch xy (translation rides in via w * offset),
    // so a pre-divided UV run through them matches vanilla's undivided texProj0 path
    vec4 uvProj = vec4(screenUV, 0.0, 1.0);

    // sky layer: average of minecraft:textures/environment/end_sky.png (128x128, 26.2)
    const vec3 SKY = vec3(0.4522, 0.3440, 0.6110);
    vec3 color = SKY * COLORS[0];
    for (int i = 0; i < PORTAL_LAYERS; i++) {
        color += textureProj(Sampler0, uvProj * end_portal_layer(float(i + 1))).rgb * COLORS[i];
    }

    // Fullbright like the other screen-space modes: the portal reads identically in
    // any light. ColorModulator carries any global tint.
    fragColor = vec4(color, 1.0) * ColorModulator;
}
