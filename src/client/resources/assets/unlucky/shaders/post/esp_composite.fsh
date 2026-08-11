#version 330

// ESP composite: turns the entity-outline mask into a border plus an optional interior
// fill, in a single pass.
//
// Built around a signed distance field:
//
//   d > 0   inside the silhouette, distance in texels to the nearest empty texel
//   d < 0   outside it, distance to the nearest covered texel
//
// The smoothsteps over d ARE the antialiasing. The mask is rasterized without MSAA, so
// its edges are hard on/off; deriving sub-pixel coverage from the distance recovers a
// smooth edge. That edge quality is the single thing that separated Future's outline
// from ours — theirs came free from GL_LINE_SMOOTH on a stencil-clipped wireframe hull,
// and 26.2 has none of the three pieces that made that work (no stencil in
// DepthStencilState, no GL_LINE_SMOOTH in core profile, no hardware line width).
// Computing coverage analytically beats all three anyway: thickness is continuous rather
// than quantized to whole texels or line widths.
//
// Two modes, over the same distance field:
//
//   Outline  a crisp band lying just INSIDE the edge. Drawing inward matters — an
//            outward band grows every object by its width and completely swallows any
//            feature thinner than twice it, so llama legs and distant mobs collapse into
//            solid blobs.
//   Glow     a soft halo straddling the edge, falling off linearly outward.
//
// Fill is independent of both, and defaults off. Every reference client ships it that
// way: Future's menu shows Filled off with a blend of 0.02 when enabled, and Meteor
// carries fillOpacity as its own uniform.
//
// Output is straight (non-premultiplied) colour with alpha as coverage, because the
// composite back onto the scene uses BlendFunction.ENTITY_OUTLINE_BLIT, which is
// (SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ZERO, ONE).

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform EspConfig {
    // x = width in texels, y = border alpha, z = fill opacity, w = mode (0 outline, 1 glow)
    vec4 EspParams;
};

in vec2 texCoord;

out vec4 fragColor;

// Ceiling for the search. The radius actually used is derived from the width each frame:
// this is a fullscreen pass, so an oversized kernel is paid for on every pixel of every
// frame whether or not anything is highlighted. Pinning it at the maximum cost ~113
// texture fetches per pixel — around 234 million per frame at 1080p — when a one-pixel
// border needs 13.
const int MAX_RADIUS = 6;
const float MAX_WIDTH = 6.0;
const float COVERAGE_EPSILON = 0.004;
// Stands in for "no sample of the opposite kind anywhere in the kernel", larger than any
// distance the kernel can produce so the smoothsteps saturate as they should.
const float FAR = 64.0;

float covered(vec4 texel) {
    return step(COVERAGE_EPSILON, max(max(texel.r, texel.g), texel.b));
}

void main() {
    vec2 oneTexel = 1.0 / InSize;
    float width = clamp(EspParams.x, 0.5, MAX_WIDTH);
    bool glowMode = EspParams.w > 0.5;

    vec4 center = texture(InSampler, texCoord);
    bool centerCovered = covered(center) > 0.5;

    // Only search as far as the band actually reaches. One texel past the width covers
    // the far shoulder of the smoothstep; anything beyond that cannot change the result,
    // because both the inward term and the glow ramp have already saturated.
    int radius = min(int(ceil(width)) + 1, MAX_RADIUS);
    float radiusSq = float(radius * radius);

    float nearestOppositeSq = FAR * FAR;
    vec3 nearestColor = center.rgb;

    for (int y = -radius; y <= radius; y++) {
        for (int x = -radius; x <= radius; x++) {
            float distSq = float(x * x + y * y);
            // circular kernel: a square one measures corners as further out than edges,
            // which shows up as visibly thicker corners on the band
            if (distSq > radiusSq || distSq >= nearestOppositeSq) {
                continue;
            }
            vec4 s = texture(InSampler, texCoord + vec2(float(x), float(y)) * oneTexel);
            if ((covered(s) > 0.5) != centerCovered) {
                nearestOppositeSq = distSq;
                // outside the silhouette the nearest opposite IS the nearest covered
                // texel, which is exactly the colour the antialiased fringe should take
                if (!centerCovered) {
                    nearestColor = s.rgb;
                }
            }
        }
    }

    float distance = sqrt(nearestOppositeSq) * (centerCovered ? 1.0 : -1.0);

    float coverage = smoothstep(-0.5, 0.5, distance);
    // 1 near the edge, falling to 0 once we are `width` texels inside
    float inward = 1.0 - smoothstep(width - 0.5, width + 0.5, distance);

    float band;
    if (glowMode) {
        // linear ramp outward, clipped by the inward term so the deep interior stays dark
        band = min(clamp(1.0 + distance / width, 0.0, 1.0), inward);
    } else {
        band = coverage * inward;
    }

    float alpha = max(band * EspParams.y, coverage * EspParams.z);
    if (alpha <= 0.0) {
        // write transparent rather than discarding: the swap target is not guaranteed to
        // be cleared before this pass, and a discard would leave last frame's texel
        fragColor = vec4(0.0);
        return;
    }

    // inside the silhouette the texel carries its own object's colour; in the antialiased
    // fringe outside it, borrow the nearest covered neighbour's
    vec3 color = centerCovered ? center.rgb : nearestColor;

    fragColor = vec4(color, alpha);
}
