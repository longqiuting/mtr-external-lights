#version 150

// Screen-space lamp scattering - fragment stage.
//
// One pass over the visible frame. For every pixel the view ray is rebuilt from the scene depth, and
// every lamp near the camera contributes the part of its glowing volume that this ray crosses:
//
//     ray      rd = normalize(far - near),  t in [0, min(sceneDepth, MaxRay)]
//     chord    the segment of the ray inside the lamp's sphere (radius R); its length is how much
//              lit air the pixel looks through - maximum through the centre, zero at the rim
//     cone     the lamp's own heading gates that volume, plus a halo right at the lamp
//     window   fades the far end of the beam so it cannot pop
//     phase    Henyey-Greenstein forward scattering, normalised so g = 0 gives 1
//     trans    exp(-k * distance to the lamp): the medium between lamp and point absorbs
//
//     tau   = (chord / R) * cone * window * phase * trans * intensity
//     out   = 1 - exp(-sum(tau) * gain)          per channel, added over the scene
//
// The exponential is what keeps a bright lamp from clipping to a white card: each channel saturates
// at 1 independently. The pass never samples the colour attachment (it writes to it), so no scene
// colour is read here - by construction, not by choice.

uniform sampler2D Sampler0;                 // scene depth copy, window space [0,1] in .r
uniform mat4 uInvViewProj;                  // inverse(projection * view), camera relative
uniform vec4 uParams;                       // x = gain, y = extinction/block, z = max ray, w = phase g
uniform vec4 uCone;                         // x = cos(inner), y = cos(outer)
uniform int uCount;                         // lamps actually filled in
uniform vec4 uLightPos[16];                 // xyz = lamp, camera relative; w = glow radius R
uniform vec4 uLightDir[16];                 // xyz = unit heading; w = beam length
uniform vec4 uLightColor[16];               // rgb = colour; w = intensity

in vec2 vUv;
out vec4 fragColor;

const float PHASE_MAX = 4.0;

float smooth01(float x) {
    float t = clamp(x, 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

void main() {
    vec2 ndc = vUv * 2.0 - 1.0;
    float sceneDepth = texture(Sampler0, vUv).r;

    // The near/far pair gives the ray direction; the depth tells where the ray stops. With
    // depth == 1 there is no surface (sky) and the ray runs to MaxRay, so a beam drawn against the
    // sky still scatters - the case where the effect matters most at night.
    vec4 nearClip = uInvViewProj * vec4(ndc, -1.0, 1.0);
    vec4 farClip = uInvViewProj * vec4(ndc, 1.0, 1.0);
    vec3 nearPoint = nearClip.xyz / nearClip.w;
    vec3 farPoint = farClip.xyz / farClip.w;
    vec3 rd = normalize(farPoint - nearPoint);

    float maxRay = uParams.z;
    float tHit = maxRay;
    if (sceneDepth < 0.9999995) {
        vec4 surface = uInvViewProj * vec4(ndc, sceneDepth * 2.0 - 1.0, 1.0);
        if (abs(surface.w) > 1.0e-7) {
            tHit = clamp(length(surface.xyz / surface.w), 0.02, maxRay);
        }
    }

    float gain = uParams.x;
    float extinction = uParams.y;
    float g = clamp(uParams.w, 0.0, 0.92);
    float g2 = g * g;
    float cosInner = uCone.x;
    float cosOuter = uCone.y;

    vec3 sum = vec3(0.0);

    for (int i = 0; i < 16; i++) {
        if (i >= uCount) {
            break;
        }

        vec4 pos = uLightPos[i];
        vec4 dir = uLightDir[i];
        vec4 tint = uLightColor[i];

        float power = tint.w;
        if (power <= 1.0e-4) {
            continue;
        }
        vec3 lamp = pos.xyz;
        float radius = max(pos.w, 0.05);
        vec3 axis = normalize(dir.xyz);
        float beamLength = max(dir.w, 0.5);

        // Closest approach of this pixel's ray to the lamp, then the chord of its sphere.
        float tClosest = dot(lamp, rd);
        float perp2 = dot(lamp, lamp) - tClosest * tClosest;
        if (perp2 >= radius * radius) {
            continue;                            // the ray misses this lamp's volume
        }
        float halfChord = sqrt(max(radius * radius - perp2, 0.0));
        float t0 = max(tClosest - halfChord, 0.0);
        float t1 = min(tClosest + halfChord, tHit);
        if (t1 <= t0) {
            continue;                            // behind the eye, or a surface cuts it all off
        }
        float chord = t1 - t0;

        // Midpoint of the visible chord: where the scattering is evaluated.
        vec3 mid = rd * (0.5 * (t0 + t1));
        vec3 toMid = mid - lamp;
        float dist = length(toMid);
        vec3 midDir = dist > 1.0e-4 ? toMid / dist : axis;

        // Cone gate: the lamp only lights the air it is pointed at. The halo term keeps the air
        // immediately around the lamp glowing in every direction (that is the flare's own volume).
        float cosAngle = dot(midDir, axis);
        float cone = smooth01((cosAngle - cosOuter) / max(cosInner - cosOuter, 1.0e-3));
        float halo = 1.0 - smooth01(dist / radius);
        cone = max(cone, halo);

        // Axial window: nothing past the end of the beam, so the beam length is a real cut.
        float axial = dot(toMid, axis);
        float window = 1.0 - smooth01((axial - beamLength * 0.6) / max(beamLength * 0.4, 1.0e-3));
        window = max(window, halo);

        // Forward scattering: the light travels along the axis, the camera looks along -rd.
        float cosTheta = clamp(-dot(axis, rd), -1.0, 1.0);
        float denom = max(1.0 + g2 - 2.0 * g * cosTheta, 1.0e-3);
        float phase = min((1.0 - g2) / pow(denom, 1.5), PHASE_MAX);

        // Attenuation between the lamp and the scattering point.
        float trans = exp(-extinction * dist);

        // Inverse-square-ish illumination: the lamp lights the medium at `dist` with irradiance
        // ~1/dist^2, so the air right at the lamp is much brighter than the air three blocks away.
        // The 9 (3 blocks squared) keeps the near field from exploding - at dist = 3 the medium is
        // at half strength, at 6 a fifth.
        float falloff = 1.0 / (1.0 + dist * dist / 9.0);

        // 0.125 is the one calibration constant of the model: with chord/R in [0,2], a phase near 4
        // head-on and falloff 1 at the lamp, one lamp staring at the camera lands at tau = 1, i.e.
        // lit = 1 - exp(-1.6) = 0.80. A pair of head lamps reads 0.9 (bright, not clipped) and a
        // pixel three blocks to the side of them 0.6, so the edge stays soft instead of a white disc.
        float tau = (chord / radius) * cone * window * phase * trans * power * falloff * 0.125;
        sum += tint.rgb * tau;
    }

    if (sum.r + sum.g + sum.b < 1.0e-4) {
        discard;                                 // additive: nothing to add, nothing to write
    }
    vec3 lit = 1.0 - exp(-sum * gain);
    fragColor = vec4(lit, 1.0);
}
